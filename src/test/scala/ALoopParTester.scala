package gps

import dsptools.DspTester
import dsptools.numbers._
import org.scalatest.{FlatSpec, Matchers}
import chisel3._
import chisel3.experimental.FixedPoint
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}
import scala.math._


class ALoopParSpec extends FlatSpec with Matchers {
  behavior of "ALoopPar"

  val nHalfFreq = 0
  val freqStep = 500
  val fsample = 16367600
  val fcarrier = 4128460
  val fchip = 1023000
  val nSample = 16368
  val CPStep = 1
  val CPMin = 1320
//  val nCPSample = ((nSample - CPMin - 1) / CPStep).toInt + 1
  val nCPSample = 40

  val params = EgALoopParParams(
    wADC = 4,
    wCA = 4,
    wNCOTct = 4,
    wNCORes = 32,
    nSample = nSample,
    nLoop = 1,
    nFreq = 2 * nHalfFreq + 1,
    nCPSample = nCPSample,
    CPMin = CPMin,
    CPStep = CPStep,
    freqMin = fcarrier - nHalfFreq * freqStep,
    freqStep = freqStep,
    fsample = fsample,
    fchip = fchip,
  )
  it should "ALoop" in {
    val baseTrial = ALoopParTestVec(idx_sate=0)
    val idx_sate = Seq(22)
    val trials = idx_sate.map { idx_sate => baseTrial.copy(idx_sate = idx_sate) }
    ALoopParTester(params, trials) should be (true)
  }


}



/**
 * Case class holding information needed to run an individual test
 */
case class ALoopParTestVec(
  idx_sate: Int,
  optFreq: Option[Int] = None,
  optCP: Option[Int] = None,
  sateFound: Option[Boolean] = None,
)

/**
 * DspTester for acquisition loop
 *
 * Run each trial in @trials
 */
class ALoopParTester[T <: chisel3.Data](c: ALoopPar[T], trials: Seq[ALoopParTestVec], tolLSBs: Int = 1)
  extends DspTester(c) {




  val byteArray = Files.readAllBytes(Paths.get("python/data/gioveAandB_short.bin"))

  for (trial <- trials) {


    poke(c.io.in.valid, 0)
    poke(c.io.in.bits.idx_sate, trial.idx_sate)
    poke(c.io.out.ready, 0)
    poke(c.io.in.bits.debugCA, 0)
    poke(c.io.in.bits.debugNCO, 0)

    // wait until input is accepted
    var cycles = 0


    print("trial")
    updatableDspVerbose.withValue(false) {
      while (cycles < 35000) {

        if (cycles == 1) {
          poke(c.io.in.valid, 1)
        }
        else {
          poke(c.io.in.valid, 0)
        }


        val data_ADC = math.cos((2 * Pi) * (cycles) / 32) * 4
        val data_CA_pre = math.cos((2 * Pi) * (cycles - 10) / 32) * 4
        var data_CA = 0.0
        if (data_CA_pre > 0.0) {
          data_CA = 1.0
        }
        else {
          data_CA = -1.0
        }
        //      val data_CA = 1.0
        val data_cos = 1.0
        val data_sin = 0.0

        val temp = 0
        val data_ADC_real = byteArray(cycles + temp * 16368).toInt

        poke(c.io.in.bits.ADC, data_ADC_real)
        poke(c.io.in.bits.CA, data_CA)
        poke(c.io.in.bits.cos, data_cos)
        poke(c.io.in.bits.sin, data_sin)

        if (peek(c.io.out.valid)) {
          peek(c.io.out.bits.freqOpt)
          peek(c.io.out.bits.CPOpt)
          peek(c.io.out.bits.sateFound)
          peek(c.io.out.bits.max)
          peek(c.io.out.bits.sum)
        }

        cycles += 1
        step(1)


      }
    }


  }
}



/**
  * Convenience function for running tests
  */
object ALoopParTester {
  def apply(params: ALoopParParams[SInt], trials: Seq[ALoopParTestVec]): Boolean = {
//    chisel3.iotesters.Driver.execute(Array("-tbn", "verilator", "-fiwv", "-fimed", "1000000000000"), ()
      chisel3.iotesters.Driver.execute(Array("-tbn", "treadle", "-fiwv", "-fimed", "1000000000000"), ()
    => new ALoopPar[SInt](params)) {
//    dsptools.Driver.execute(() => new ACtrl(params), TestSetup.dspTesterOptions) {
      c => new ALoopParTester(c, trials)
    }
  }
}




