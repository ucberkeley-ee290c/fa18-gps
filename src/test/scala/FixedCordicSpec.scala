package gps

import org.scalatest.{FlatSpec, Matchers}


class FixedCordicSpec extends FlatSpec with Matchers {
  behavior of "FixedIterativeCordic"

  val params = FixedCordicParams(
    xyWidth = 32,
    xyBPWidth = 16,
    zWidth = 32,
    zBPWidth = 20,
    correctGain = true,
    nStages = 32,
    stagesPerCycle = 1,
    calAtan2 = false,
    dividing = true,
  )

  def angleMap(phi: Double): Double = {
    if (phi > math.Pi / 2)
      phi - math.Pi
    else
      if (phi < -math.Pi / 2)
        phi + math.Pi
      else
        phi
  }

  val baseTrial = ABC(xin = 0.0, yin = 0.0, zin = 0.0, vectoring = true)
  var yin = Seq(1, 2, 4, 8, 16, 31, 57)
  var angles = Seq(-3.0, -2.0, -1.0, -0.0, -1.0, 2.0, 3.0)

  val dividingTrials =
    // check dividing
    yin.map { y =>
      baseTrial.copy(xin = -29, yin = y, zout = Some(y / -29.0))
    }

  val atan2Trials =
    // check atan2
    angles.map { phi =>
      baseTrial.copy(xin = 15 * math.cos(phi), yin = 15 * math.sin(phi), zout = Some(phi))
    }

  val atanTrials =
    // check atan
    angles.map { phi =>
      baseTrial.copy(xin = 15 * math.cos(phi), yin = 15 * math.sin(phi), zout = Some(angleMap(phi)))
    }

  it should "dividing with stagesPerCycle=1" in {
    FixedCordicTester(params.copy(calAtan2=false, dividing=true), dividingTrials) should be (true)
  }

  it should "atan2 with stagePerCycle=1" in {
    FixedCordicTester(params.copy(calAtan2=true, dividing=false), atan2Trials) should be (true)
  }

  it should "atan with stagePerCycle=1" in {
    FixedCordicTester(params.copy(calAtan2=false, dividing=false), atanTrials) should be (true)
  }
}
