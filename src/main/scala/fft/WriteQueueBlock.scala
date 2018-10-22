package fft

import chisel3._
import chisel3.experimental._
import chisel3.util._
import dspblocks._
import dsptools._
import dsptools.numbers._
import dspjunctions._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

/**
  * The memory interface writes entries into the queue.
  * They stream out the streaming interface
  * @param depth number of entries in the queue
  * @param streamParameters parameters for the stream node
  * @param p
  */
abstract class WriteQueue
(
  val depth: Int = 8,
  val streamParameters: AXI4StreamMasterParameters = AXI4StreamMasterParameters()
)(implicit p: Parameters) extends LazyModule with HasCSR {
  // stream node, output only
  val streamNode = AXI4StreamMasterNode(streamParameters)

  lazy val module = new LazyModuleImp(this) {
    require(streamNode.out.length == 1)

    // get the output bundle associated with the AXI4Stream node
    val out = streamNode.out(0)._1
    val out_bits = out.bits.data.asTypeOf(Vec(2, DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP))))
    // width (in bits) of the output interface
    val width = out.params.n * 8
    // instantiate a queue
    val queue0 = Module(new Queue(DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP)), depth))
    val queue1 = Module(new Queue(DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP)), depth))
    // connect queue output to streaming output
    out.valid := (queue0.io.deq.valid && queue1.io.deq.valid)
    out_bits(0) := queue0.io.deq.bits
    out_bits(1) := queue1.io.deq.bits
    out.bits.data := out_bits.asUInt()
    // don't use last
    out.bits.last := false.B
    // Queue ready to deq when out is ready and the other queue is not valid (i.e., transaction not occurring on other queue).
    queue0.io.deq.ready := (out.ready && !(queue1.io.deq.valid))
    queue1.io.deq.ready := (out.ready && !(queue0.io.deq.valid))

    // We need to make the enq a UInt to satisfy regmap, and Decoupled to break out ready, valid, bits.
    val enq0 = Wire(Decoupled(UInt(24.W)))
    queue0.io.enq.valid := enq0.valid
    queue0.io.enq.bits := enq0.bits.asTypeOf(DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP)))
    enq0.ready := queue0.io.enq.ready

    val enq1 = Wire(Decoupled(UInt(24.W)))
    queue1.io.enq.valid := enq1.valid
    queue1.io.enq.bits := enq1.bits.asTypeOf(DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP)))
    enq1.ready := queue1.io.enq.ready

    regmap(
      // each write adds an entry to the queue
      0x0 -> Seq(RegField.w(width, enq0)),
      // read the number of entries in the queue
      (width+7)/8 -> Seq(RegField.w(width, enq1)),
    )
  }
}

/**
  * TLDspBlock specialization of WriteQueue
  * @param depth number of entries in the queue
  * @param csrAddress address range for peripheral
  * @param beatBytes beatBytes of TL interface
  * @param p
  */
class TLWriteQueue
(
  depth: Int = 8,
  csrAddress: AddressSet = AddressSet(0x2000, 0xff),
  beatBytes: Int = 8,
)(implicit p: Parameters) extends WriteQueue(depth) with TLHasCSR {
  val devname = "tlQueueIn"
  val devcompat = Seq("ucb-art", "dsptools")
  val device = new SimpleDevice(devname, devcompat) {
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping)
    }
  }
  // make diplomatic TL node for regmap
  override val mem = Some(TLRegisterNode(address = Seq(csrAddress), device = device, beatBytes = beatBytes))
}