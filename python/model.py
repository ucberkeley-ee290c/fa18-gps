import numpy as np
import matplotlib.pyplot as plt

from blocks import *


raw_data = np.fromfile('adc_sample_data.bin', dtype=np.int8)

# Data sample rate
fs = 1.023*16*1e6

# The data contains the following SV's with at the following frequencies:
# The frequencies are in MHz
sv_list = [22, 3, 19, 14, 18, 11, 32, 6]
sv_freqs = [4.128460, 4.127190, 4.129280,
            4.133130, 4.127310, 4.133280,
            4.134060,4.127220]

sv = sv_list[0]
sv_freq = sv_freqs[0]

# We'll start off with the NCO Width set to 10
nco_width = 10
ideal_carrier_nco_code = sv_freq*1e6 * (2**nco_width) / fs
print(f"""Ideal NCO frequency for SV {sv} with frequency {sv_freq} is
{ideal_carrier_nco_code}""")
carrier_nco_code = round(ideal_carrier_nco_code)
print(f"Rounding the NCO frequency to {carrier_nco_code}")

def main():
    num_cycles = len(raw_data)

    adc = ADC(raw_data)
    nco_carrier = NCO(10, False)

    # Technically don't need to make multiple multiplier objects as they all
    # behave the same. But in the code we are creating multiple object
    # instances to know how many hardware multipliers we will need. 
    multI = MUL()
    multQ = MUL()

    multIe = MUL()
    multIp = MUL()
    multIl = MUL()
    multQe = MUL()
    multQp = MUL()
    multQl = MUL()
    
    ca = CA()
    
    intdumpI = IntDump()
    intdumpQ = IntDump()

    # ki = 1, kp = 1, first discriminator
    dll = DLL(1, 1, 1)
    # Disabling the Costas loop for now and forcing the right frequency
    costas = Costas(1,[1,1,1],1,1,1)    

    nco_code = NCO(10, True)
    packet = Packet()
    
    # FIXME: Initial DLL and Costas loop values
    dll_out = carrier_nco_code
    costas_out = carrier_nco_code
    last_integ_I = [0,0,0]
    last_integ_Q = [0,0,0]

    for x in range(0, num_cycles):
        adc_data = adc.update()
        cos_out, sin_out  = nco_carrier.update(costas_out)        
        I = multI.update(adc_data, cos_out) 
        Q = multQ.update(adc_data, sin_out)

        f_out, f2_out = nco_code.update(dll_out)
        e, p, l, dump = ca.update(f_out, sv, 22)

        I_e = multIe.update(I, e)        
        I_p = multIp.update(I, p)
        I_l = multIl.update(I, l)
        Q_e = multQe.update(Q, e)
        Q_p = multQp.update(Q, p)
        Q_l = multQl.update(Q, l)

        I_sample = [I_e, I_p, I_l]
        Q_sample = [Q_e, Q_p, Q_l]

        # I_int and Q_int are lists of size 3
        I_int = intdumpI.update(I_sample, dump)
        Q_int = intdumpQ.update(Q_sample, dump)

        if dump:
            dll_out = dll.update(last_integ_I, last_integ_Q, carrier_nco_code, 0)
            print(dll_out)
        # Commenting this out for now
        # costas_out = costas.update(I_int[1], I_int[1], 0)

        last_integ_I = I_int
        last_integ_q = Q_int
        # packet.update(x, I_int, Q_int)


if __name__ == "__main__": 
    main()

