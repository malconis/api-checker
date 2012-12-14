package com.rackspace.com.papi.components.checker

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.FilterChain

import java.lang.management._
import javax.management._

import javax.xml.transform._
import javax.xml.transform.sax._
import javax.xml.transform.stream._
import javax.xml.transform.dom._
import javax.xml.validation._

import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.Reader
import java.io.StringWriter

import scala.xml._

import com.rackspace.com.papi.components.checker.wadl.StepBuilder
import com.rackspace.com.papi.components.checker.wadl.WADLDotBuilder

import com.rackspace.com.papi.components.checker.step.Step
import com.rackspace.com.papi.components.checker.step.Result

import com.rackspace.com.papi.components.checker.handler.ResultHandler

import com.rackspace.com.papi.components.checker.servlet._

import com.rackspace.com.papi.components.checker.util.IdentityTransformPool

import org.w3c.dom.Document

import org.apache.commons.codec.digest.DigestUtils.sha1Hex

import com.yammer.metrics.scala.Instrumented
import com.yammer.metrics.scala.Meter
import com.yammer.metrics.scala.Timer
import com.yammer.metrics.scala.MetricsGroup
import com.yammer.metrics.util.PercentGauge

class ValidatorException(msg : String, cause : Throwable) extends Throwable(msg, cause) {}

object Validator {
  def apply (name : String, startStep : Step, config : Config) : Validator = {
    val validator = new Validator(name, startStep, config)
    config.resultHandler.init(validator, None)
    validator
  }

  def apply (name : String, in : Source, config : Config = new Config) : Validator = {
    val builder = new StepBuilder()
    val transformerFactory = TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl", null)

    if (!transformerFactory.getFeature(SAXTransformerFactory.FEATURE)) {
      throw new RuntimeException("Need a SAX-compatible TransformerFactory!")
    }

    val transHandler = transformerFactory.asInstanceOf[SAXTransformerFactory].newTransformerHandler()
    val domResult = new DOMResult
    transHandler.setResult(domResult)
    val step = builder.build(in, new SAXResult(transHandler), config)

    val validator = new Validator(name, step, config)
    val checker = domResult.getNode.asInstanceOf[Document]
    val checkerMBean = new Checker(validator, checker)

    config.resultHandler.init(validator, Some(checker))
    validator
  }

  def apply (name : String, in : Source, resultHandler : ResultHandler) : Validator = {
    val config = new Config
    config.resultHandler = resultHandler

    apply(name, in, config)
  }

  def apply (name : String, in : (String, InputStream), config : Config) : Validator = {
    apply (name, new StreamSource(in._2, in._1), config)
  }

  def apply (name : String, in : InputStream, config : Config) : Validator = {
    apply (name, ("test://path/to/mywadl.wadl", in), config)
  }

  //
  // The following are for backward compatability
  //
  def apply (startStep : Step, config : Config) : Validator = apply(null, startStep, config)
  def apply (in : Source, config : Config) : Validator =  apply(null, in, config)
  def apply (in : Source) : Validator =  apply(null, in, new Config)
  def apply (in : Source, resultHandler : ResultHandler) : Validator = apply(null, in, resultHandler)
  def apply (in : (String, InputStream), config : Config) : Validator = apply(null, in, config)
  def apply (in : InputStream, config : Config) : Validator = apply (null, in, config)
}


trait CheckerMBean {
  def checkerXML : String
  def checkerDOT : String
  def getXmlSHA1 : String
  def getDotSHA1 : String
}

class Checker (private val validator : Validator, private val checker : Document) extends CheckerMBean {

  private val mbs = ManagementFactory.getPlatformMBeanServer()
  private val name = new ObjectName("\"com.rackspace.com.papi.components.checker\":type=\"Validator\",scope=\""+
                            validator.name+"\",name=\"checker\"")
  mbs.registerMBean(this, name)

  private val xml = {
    val transformer = IdentityTransformPool.borrowTransformer
    try {
      val writer = new StringWriter()
      transformer.setOutputProperty (OutputKeys.INDENT, "yes")
      transformer.transform (new DOMSource(checker), new StreamResult(writer))
      writer.toString
    } finally {
      if (transformer != null) {
        IdentityTransformPool.returnTransformer(transformer)
      }
    }
  }

  private val xmlSHA1 = sha1Hex(xml)

  private val dot = {
    val writer = new StringWriter()
    val dotBuilder = new WADLDotBuilder()

    dotBuilder.buildFromChecker (new DOMSource(checker), new StreamResult (writer), true, true)
    writer.toString
  }

  private val dotSHA1 = sha1Hex(dot)

  override def checkerXML = xml
  override def checkerDOT = dot
  override def getXmlSHA1 = xmlSHA1
  override def getDotSHA1 = dotSHA1
}

class Validator private (private val _name : String, val startStep : Step, val config : Config) extends Instrumented {

  val name = {
    if (_name == null) {
      Integer.toHexString(hashCode())
    } else {
      _name
    }
  }

  private class ValidatorFailGauge(private val timer : Timer,
                                   private val failMeter : Meter) extends PercentGauge {

    override def getNumerator = failMeter.oneMinuteRate
    override def getDenominator = timer.oneMinuteRate
  }

  private val timer = metrics.timer("validation-timer", name)
  private val failMeter = metrics.meter("fail-meter", "fail", name)
  private val failGauge =  metricsRegistry.newGauge(getClass(), "fail-rate", name,
                                                   (new ValidatorFailGauge(timer, failMeter)))

  private val resultHandler = {
    if (config == null) {
      (new Config).resultHandler
    } else {
      config.resultHandler
    }
  }

  def validate (req : HttpServletRequest, res : HttpServletResponse, chain : FilterChain) : Result = {
    val context = timer.timerContext()
    try {
      val creq = new CheckerServletRequest (req)
      val cres = new CheckerServletResponse(res)
      val result = startStep.check (creq, cres, chain, 0).get
      resultHandler.handle(creq, cres, chain, result)
      if (!result.valid) failMeter.mark()
      result
    } catch {
      case v : ValidatorException => throw v
      case e => throw new ValidatorException("Error while validating request", e)
    } finally {
      context.stop
    }
  }
}
