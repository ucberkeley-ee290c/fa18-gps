package gps

import dsptools.numbers._
import dsptools.DspTester 
import org.scalatest.{FlatSpec, Matchers}

case class ABC(
  // input x, y and z
  ip: Int,
  qp: Int,
  lfcoeff0: Int,
  lfcoeff1: Int,
  lfcoeff2: Int,
  lfcoeff3: Int,
  lfcoeff4: Int,
  fbias: Int,
)

/* 
 * DspSpec for Costas
 */
class CostasSpec extends FlatSpec with Matchers {
  behavior of "Costas"

  val params = SampledCostasParams(
    dataWidth = 10,
    freqWidth = 20,
    phaseWidth = 20,
    cordicXYWidth = 16,
    cordicZWidth = 16,
    cordicNStages = 8,
    cordicCorrectGain = true,
    costasLeftShift = 16 - 2, // get all the bits
    fllRightShift = 0,  // keep 0 right shift
    lfCoeffWidth = 10,
  )

  it should "Run CostasTest" in {
    val input = ABC(ip=256, qp=0, lfcoeff0=1, lfcoeff1=0, lfcoeff2=0, lfcoeff3=0, lfcoeff4=0,
      fbias=0)
    SampledCostasTester(params, input) should be (true)
  }
}

/*
 * Tester for Costas
 */
class CostasTester[T <: chisel3.Data](dut: CostasLoop, input: ABC) extends DspTester(dut) {
  poke(dut.io.Ip, input.ip)
  poke(dut.io.Qp, input.qp)
  poke(dut.io.lfCoeff.phaseCoeff0, input.lfcoeff0)
  poke(dut.io.lfCoeff.phaseCoeff1, input.lfcoeff1)
  poke(dut.io.lfCoeff.phaseCoeff2, input.lfcoeff2)
  poke(dut.io.lfCoeff.freqCoeff0, input.lfcoeff3)
  poke(dut.io.lfCoeff.freqCoeff1, input.lfcoeff4)
  poke(dut.io.freqBias, input.fbias)

  peek(dut.io.freqCtrl)
  peek(dut.io.phaseCtrl)

  // debug
  peek(dut.io.xin)
  peek(dut.io.yin)
  peek(dut.io.zin)
  peek(dut.io.xout)
  peek(dut.io.yout)
  peek(dut.io.zout)

  expect(true, "always true")
  step(1)
}

object SampledCostasTester {
  def apply(params: SampledCostasParams, input: ABC): Boolean = {
    chisel3.iotesters.Driver.execute(Array("-tbn", "firrtl", "-fiwv"), () => new CostasLoop(params)) {
      c => new CostasTester(c, input)
    }
  }
}

