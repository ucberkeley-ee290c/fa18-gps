# Tapein 2:

## FFT Generator
The figure below shows the FFT search diagram. Two FFT blocks take DATA and C/A Code and perform FFT on these two signal. The result is multiplied in frequency domain. Then the mulitplication result is proccessed by the IFFT block.
Basically the generators work in a way that the correct order inputs (from DATA and CA code) are processed by the FFT generator in FFT and normal input configuration. After that, the output order is not very straightforward because of the two stage topology.
For large input points, pipelined topology is necessary to reduce the hardware cost, and the extra delay and memory for storing and reordering the bit order is non-ideal. 
In order to support IFFT in acquisition process of GPS, the FFT generator need handle the output properly, unless it have to store the pipelined output than unsramble it. 
So the IFFT configuration should be able to directly take the scrambled output from FFT and perform the IFFT to get the correct result with right order.
In this way, entire searching proccess can work continuously from the input of DATA to the output result.

![FFT_Search_Diagram](pictures/fft/fft-search-diagram.png)

### Parameters
```scala
  genIn: DspComplex[T],
  genOut: DspComplex[T],
  n: Int = 16, 
  pipelineDepth: Int = 0,
  lanes: Int = 8,
  quadrature: Boolean = true,
  inverse: Boolean = false,
  unscrambleOut: Boolean = false,
  unscrambleIn: Boolean = false
 ```
 - genIn and genOut: data type for the input and output of FFT
 - n: number of FFT points
 - lanes: number of input, if lanes<n, each FFT takes several cycle to get the full points it need.
 - pipelineDepth: number of extra pipeline stage inserted to generator
 - inverse: inverse FFT
 - unscrambleOut: with n=lanes, it should be easy for direct form to just re-wire the output to get the right order.
 - unscrambleIn: FFT/IFFT in DIF with scrambled input.

### Support unscrambleIn option
Because some bit unscrambling happened in the middle of two stages, the final result is not pure bit-reversed.
It can be handled by the following code:(from ucb-art/fft)

```scala
\\ n: total fft points
\\ p: number of lanes
\\ bp: biplex fft points
def unscramble(in: Seq[Complex], p: Int): Seq[Complex] = {
  val n = in.size
  val bp = n/p
  val res = Array.fill(n)(Complex(0.0,0.0))
  in.grouped(p).zipWithIndex.foreach { case (set, sindex) =>
    set.zipWithIndex.foreach { case (bin, bindex) =>
      if (bp > 1) {
        val p1 = if (sindex/(bp/2) >= 1) 1 else 0
        val new_index = bit_reverse((sindex % (bp/2)) * 2 + p1, log2Up(bp)) + bit_reverse(bindex, log2Up(n))
        res(new_index) = bin
      } else {
        val new_index = bit_reverse(bindex, log2Up(n))
        res(new_index) = bin
      }
    }
  }
  res
}
```
In order to handle this scrambled bit-order input, a unscrambleIn option is added. 


#### Topology for unscrambleIn options
The figure below shows 32 points DIF FFT operation. The twiddle factors are annotated in dashed boxes.
The output bits are in bit-reverse order. With a symmetric DIF implementation the bit-reversed input can be bit-reversed again to convert back the correct order. 
The original FFT is two staged and the output bits are not in a pure bit-reversed order. But with proper "symmetrical" operation, the bit order can still be corrected in this way.

![32pFFT](pictures/fft/fft32p.png)

The symmetrical configuration is in the figure below. The IFFT use DIF butterfly.
The configuration with normal order input use biplex from as the first stage and followed by direct form. And the twiddle factors for each times the direct form is in operation is shown in the colored box. The order of loading twiddle factors is simiar to bit-reverse in group.
The configuration with scrambled input performs direct FFT first and output connect to Biplex FFT. The twiddle factor is mirrored. And the biplex FFT topology is also "mirrored" inside. 

![TwiddleDetail](pictures/fft/twiddles_detail.png)

#### Biplex 
The Biplex topology is shown in the FFT search digram. The biplex in two configuration is different in terms of switching period and delay time.
Basically, the Biplex stages are "reversed". 
The first stage is still delayed by N/2 because it need to take half of output in time to another channel.
After this, every stage's delay and swithing period is reversed compared to original Biplex FFT. The butterfly use decimation-in-frequency.
Then the output should have correct order.

The example below shows how the biplex works with 32 bit FFT and 8 lanes. 
The output from direct form is bit reversed by group, which is compatible with the order of loading twiddle factors.
For the first biplex core, the upper input order is 0,1,2,3. But the correct order should be 0,2,1,3. And the lower input have order 16,17,18,19 which ideally should be 16, 18, 17, 19. Then after the biplex operation the output should be corrected. The number here is corresponding to the input original number instead of position.
![BiplexExample](pictures/fft/biplex_example.png)

### Original FFT Topology:
#### Pipelined FFT(Radix-2 Multipath Delay Commuattor) and Biplex FFT
R2MDC is a the most straightforward approach of pipelined FFT. When a new frame arrives, first half of points are multiplexed , delayed by N/2 samples and connected to the upper input of butterfly cell. The second half of points are selected and directly connected to the lower input of butterfly, and it will arrive simutaneously with the first half data. The output of first stage triggers the second stage. The upper input of second stage connect to the upper output of the first stage for two samples and then switch the lower input of second stage to upper output of first stage. And the lower output of first stage connect to the upper input of the second stage.
In the pipelined FFT above, each stage have a swicth at twice the frequency of the previous stage and have half delay for each sample. The total required memory is N/2+N/4+N/4+...+2=3/2N-2. But in this case, the butterflies only works half the time. By using biplex structure, each biplex core takes two inputs and the butterfly works interleavely for adjacent input samples to work at full rate.

#### The original Architecture of FFT generator from ucb-art/fft
The FFT supports any power of two size of 4 or greater (n >= 4). The input rate may be divided down, resulting in a number of parallel input lanes different from the FFT size. But the input lanes (p) must be a power of 2, greater than or equal to 2, but less than or equal to the FFT size.  

When the number of parallel inputs equals the FFT size, a simple, direct form, streaming FFT is used. 
When the input is serialized, the FFT may have fewer input lanes than the size of the FFT .It is expected that the bits inputs contain time-series data time-multiplexed on the inputs, such that on the first cycle are values x[0], x[1], …, x[p-1], then the next cycle contains x[p], x[p+1], … and this continues until the input is x[n-p], x[n-p+1], …, x[n-1]. 

- These FFTs efficiently reuse hardware and memories to calculate the FFT at a slower rate but higher latency.
- Extra shift registers of n/2 at the output unscramble the data before they arrive at the direct form FFT. Pipeline registers may be inserted after each butterfly, but never at the input or output.
- A final direct form FFT sits at the output of the biplex FFTs, finishing the Fourier transform. Pipeline registers favor the direct form FFT slightly, though the critical path through this circuit is still through log2(n) butterflies, so one pipeline register per stage (a pipeline depth of log2(n)) is recommended.
- The outputs are scrambled spectral bins. Since there is some unscrambling between the biplex and direct form FFT, the output indices are not purely bit reversed. To accommodate this time multiplexing, the FFT architecture changes. Pipelined biplex FFTs are inserted before the direct form FFT.


## Other modifications
### Unscramble output option
The FFT output result in a bit-order that hard to understand. Basically because it has two stage, so the result comes out from the first stage is not completely bit-reversed order. Then those result go thru the direct form FFT. Basically, the final result is composed of several sub-groups, the order of groups are in bit-reverse order while inside each group the order also needs to be adjusted. In tapein 2, a configurable option is added for direct form only now.

So the result for direct form FFT is ready to use now.

### IFFT suport for biplex version
The original FFT generator can pass both direct FFT and biplex+direct FFT. But the IFFT cannot pass by simply changing the twiddle factor. 

Now both FFT and IFFT for both version is supported. IFFT generator can be configured thru set inverse to true. But the IFFT also expects input with correct bit order, either a different IFFT or a unscrambler for biplex+direct form FFT is needed in the future.

## FFT Multiplication Block
#### IO
- Inputs: It takes two input from the output of FFT, the interface is provided by ValidwithSync from the output of two FFT blocks which do the FFT calculation for CA code and DATA seperately 
- Output: It provides output to other blocks which should handle the interaction with IFFT, with unscrambleIn option of IFFT, it can directly output result to IFFT. The interface also provide Valid and Sync signal.

## TODOs
- Solve the bit width issue in biplex FFT.
- Handle the pipeline insertion properly.
- Verify large size of FFT/IFFT



----------------------------------------------
# Tapein 1: Python Model and FFT generator hook up
## Python Acquisition Model Use FFT Search
The dataset used for FFT searching test is this
[Samples of GNSS Signal Records](http://gfix.dk/matlab-gnss-sdr-book/gnss-signal-records/)
### Searching result
Use the dataset and simple acquisition model to test FFT search, can get the following results:
- [RESULT]#3 :FOUND LOC: freq:4127400, phase:1615
- [RESULT]#11 :FOUND LOC: freq:4133400, phase:2952
- [RESULT]#14 :FOUNDLOC: freq:4132900, phase:14537
- [RESULT]#18 :FOUND LOC: freq:4127400, phase:342
- [RESULT]#19 :FOUND LOC: freq:4129400, phase:6183
- [RESULT]#22 :FOUND LOC: freq:4128400, phase:15040

The table below is the SV infomation provided by the data source. From here we can see the phase is a little bit shift, which need tracking loop to locked the exact phase. Also, the frequency shift because of the finite doppler shift search resolution and range, and will also be covered by tracking loop.

![signal](pictures/fft/signal.png)
### Effect of different K
- Larger K will improve the signal to noise ratio of weak satellite signal and will reduce the possibility of false acquisition. 
- The result of using different K when FFT searching is list in the figure below. For K=1, there is still obvious noise floor in plot 1, but it is gradually averaged out when K increase. In this case, even K=1 can get good SNR(sufficient to get correct phase). But the number of iterations should be parameterize in higher-level control logic generator.

![fft](pictures/fft/fft-keffect.png)

### Sparse FFT
- The sparse FFT aliases the input data, which is equivalent to subsample FFT spectrum. Since for GPS application, the FFT result only have one peak ideally, the correct code phase can be obtained using the subsampled FFT result.
- For different k, the effect of Sparse FFT is tested in Python model(only Actually FFT time is accumulated, since SFFT need some extra pre-proccess of the data and it can only be done in a serial way in python).The table below shows the time for different k.

| k  | FFT time(s) | Sparse FFT time(s) |
|----|--------------|---------------------|
| 2  | 0.296140     | 0.094731            |
| 5  | 0.733505     | 0.273270            |
| 10 | 1.378309     | 0.492115            |
| 20 | 2.980500     | 1.014424            |

![sfft](pictures/fft/sfft-effect.png)

## Chisel Generator
The fft generator use existing code from
[fft-genearator](https://github.com/ucb-art/fft)
with some modifications in order to support IFFT and hook up to rocket chip.

### Modifications
#### IFFT 
- Add inverse FFT configuration option.
- The original generator's twiddle factors are hard-coded and doesn't support inverse FFT. The twiddle factors in FFTConfig.scala is modified to support IFFT options.
- The result is not divided by N(# of points) since in GPS only the relative value is important, may modify this later.
#### Tester
- Add tester that support IFFT. The ideal result is times N to compatible with generator.
- Only direct form of FFT/IFFT can pass test.

### Rocket-chip hook up
- Based on the lab2 template, hook up FFT/IFFT to rocket-chip. Add four readQueues/WriteQueues connect to FFT blocks and hook up to rocket chip to order to test FFT with C program.(From James)

Read Queue
```scala
    // instantiate a queue
    val queue0 = Module(new Queue(DspComplex(FixedPoint(18.W, 6.BP), FixedPoint(18.W, 6.BP)), depth))
    val queue1 = Module(new Queue(DspComplex(FixedPoint(18.W, 6.BP), FixedPoint(18.W, 6.BP)), depth))
    val queue2 = Module(new Queue(DspComplex(FixedPoint(18.W, 6.BP), FixedPoint(18.W, 6.BP)), depth))
    val queue3 = Module(new Queue(DspComplex(FixedPoint(18.W, 6.BP), FixedPoint(18.W, 6.BP)), depth))
    // connect streaming output to queue output
    queue0.io.enq.valid := (in.valid && !(queue1.io.enq.ready || queue2.io.enq.ready || queue3.io.enq.ready))
    queue0.io.enq.bits := in_bits(0)
    queue1.io.enq.valid := (in.valid && !(queue0.io.enq.ready || queue2.io.enq.ready || queue3.io.enq.ready))
    queue1.io.enq.bits := in_bits(1)
    queue2.io.enq.valid := (in.valid && !(queue0.io.enq.ready || queue1.io.enq.ready || queue3.io.enq.ready))
    queue2.io.enq.bits := in_bits(2)
    queue3.io.enq.valid := (in.valid && !(queue0.io.enq.ready || queue1.io.enq.ready || queue2.io.enq.ready))
    queue3.io.enq.bits := in_bits(3)
```
Write Queue
```scala
    // Instantiate queues
    val queue0 = Module(new Queue(DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP)), depth))
    val queue1 = Module(new Queue(DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP)), depth))
    val queue2 = Module(new Queue(DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP)), depth))
    val queue3 = Module(new Queue(DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP)), depth))
    // Connect queue output to streaming output
    out.valid := (queue0.io.deq.valid && queue1.io.deq.valid && queue2.io.deq.valid && queue3.io.deq.valid)
    // Create a vec for the dequeue output bits, which will later be collapsed
    val out_deq_bits = Wire(Vec(4, DspComplex(FixedPoint(12.W, 7.BP), FixedPoint(12.W, 7.BP))))
    out_deq_bits(0) := queue0.io.deq.bits
    out_deq_bits(1) := queue1.io.deq.bits
    out_deq_bits(2) := queue0.io.deq.bits
    out_deq_bits(3) := queue1.io.deq.bits
    // Collapse the dequeue bits for the streamNode
    out.bits.data := out_deq_bits.asUInt()
    // don't use last
    out.bits.last := false.B
    // Queue ready to deq when out is ready and the other queue is not valid (i.e., transaction not occurring on other queue).
    queue0.io.deq.ready := (out.ready && !(queue1.io.deq.valid || queue2.io.deq.valid || queue3.io.deq.valid))
    queue1.io.deq.ready := (out.ready && !(queue0.io.deq.valid || queue2.io.deq.valid || queue3.io.deq.valid))
    queue2.io.deq.ready := (out.ready && !(queue0.io.deq.valid || queue1.io.deq.valid || queue3.io.deq.valid))
    queue3.io.deq.ready := (out.ready && !(queue0.io.deq.valid || queue1.io.deq.valid || queue2.io.deq.valid))
```
- The FFT blocks also have memory connection and need to connect to pbus.


