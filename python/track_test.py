import numpy as np
import matplotlib.pyplot as plt
import matplotlib as mpl
mpl.rcParams['agg.path.chunksize'] = 10000
from blocks import *

if __name__ == '__main__':

    # data file
    raw_data = np.fromfile('data_gen/ADCOutput.bin', dtype=np.int8)

    # data sample rate
    fs = 16528600
    sv_num = 1  # 22 #1
    sv_freq = 4.132100e6 - 3862 - 50
    chip_rate = 1.023e6

    # code bias
    code_bias_sample_rate = 7695
    code_bias = code_bias_sample_rate   # int(code_bias_sample_rate / (fs/chip_rate))
    print("Code bias is {}".format(code_bias))

    # NCO params
    if_nco_width = 20   # 30
    carrier_count_max = 2**if_nco_width - 1
    if_nco_freq = round(sv_freq / fs * carrier_count_max)

    code_nco_width = 20     # 30
    code_count_max = 2**code_nco_width - 1
    code_nco_freq = round(chip_rate / fs * code_count_max)

    # integrate time
    int_time = 2e-3
    int_num = round(int_time * fs)

    # initial phase
    if_nco_init_phase = -math.pi/2 # + math.pi/4
    code_nco_init_phase = 0

    # dll parameters
    dll_dc_gain = 12.00
    dll_bandwidth = 10
    dll_sample_rate = 1e3
    dll_discriminator_num = 1

    # costas parameters
    costas_lf_coeff = [0.1, 0.01, 0, 1e-5, 0] # [100, 10, 0.1, 1e-2, 0]

    # num of cycles to run
    num_cycles = 16000000    # len(raw_data)

    # time keeper
    time_keeper = TimeKeeper()

    # track
    track = Track(
        raw_data=raw_data,
        if_nco_width=if_nco_width,
        if_nco_init_phase=if_nco_init_phase,
        if_nco_freq=if_nco_freq,
        code_nco_width=code_nco_width,
        code_nco_init_phase=code_nco_init_phase,
        code_nco_freq=code_nco_freq,
        sv_num=sv_num,
        code_bias=code_bias,
        dll_dc_gain=dll_dc_gain,
        dll_bandwidth=dll_bandwidth,
        dll_sample_rate=dll_sample_rate,
        dll_discriminator_num=dll_discriminator_num,
        int_num=int_num,
        costas_lf_coeff=costas_lf_coeff,
        costas_pll_mode=0,
        )

    # list to plot
    time_list = []
    I_int_list = []
    Q_int_list = []
    costas_err_list = []
    freq_err_list = []
    dll_err_list = []
    freq_list = []
    d_freq_list = []
    code_freq_list = []
    en_list = []
    I_quant_list = []
    Q_quant_list = []

    for cycle in range(num_cycles):

        # time keeper update
        track_en = time_keeper.update(reset=0, code_bias=code_bias)

        # track update
        track.update(
            if_nco_freq=if_nco_freq,
            code_nco_freq=code_nco_freq,
            sv_num=sv_num,
            code_bias=code_bias,
            int_num=int_num,
            en=track_en,
            )

        # add to list
        time_list.append(cycle / fs)
        I_int_list.append(track.I_int[1])
        Q_int_list.append(track.Q_int[1])

        I_quant_list.append(track.Ips_quant)
        Q_quant_list.append(track.Qps_quant)

        # costas error and frequency error
        costas_err_list.append(track.costas.costas_err)
        freq_err_list.append(track.costas.freq_err)

        # dll error
        dll_err_list.append(track.dll_lf_out)

        # IF d_freq and freq
        d_freq_list.append(track.costas.d_lf_out / carrier_count_max * fs)
        freq_list.append(track.costas.lf_out / carrier_count_max * fs)

        # code nco freq
        code_freq_list.append(track.dll_out / code_count_max * fs)

        # en
        en_list.append(track_en)

        # get completion amount
        if cycle / num_cycles * 10 % 1 == 0:
            print("Percentage: {0:.0f}% ".format(cycle / num_cycles * 100) +
                  ">>" * int(cycle / num_cycles * 10))
    print("Percentage: 100% " + ">>" * 10)

    # plot data
    plt.close("all")

    plt.figure()
    plt.title("Costas Loop Locking")
    plt.subplot(4, 1, 1)
    plt.plot(time_list, I_int_list)
    plt.plot(time_list, Q_int_list)
    plt.legend(["I_int", "Q_int"])
    plt.subplot(4, 1, 2)
    plt.plot(time_list, costas_err_list)
    plt.legend(["Costas error"])
    plt.subplot(4, 1, 3)
    plt.plot(time_list, freq_err_list)
    plt.legend(["Freq error"])
    plt.subplot(4, 1, 4)
    plt.plot(time_list, freq_list)
    plt.legend(["IF Frequency"])
    plt.show(block=False)

    plt.figure()
    plt.title("DLL Loop Locking")
    plt.subplot(3, 1, 1)
    plt.plot(time_list, I_int_list)
    plt.plot(time_list, Q_int_list)
    plt.legend(["I_int", "Q_int"])
    plt.subplot(3, 1, 2)
    plt.plot(time_list, dll_err_list)
    plt.legend(["DLL error"])
    plt.subplot(3, 1, 3)
    plt.plot(time_list, code_freq_list)
    plt.legend(["Code NCO frequency"])
    plt.show(block=False)

    plt.figure()
    plt.title("EN signal")
    plt.plot(time_list, en_list)
    plt.show(block=False)

    plt.figure()
    plt.title("Quantized Data")
    plt.subplot(2, 1, 1)
    plt.plot(time_list, I_int_list)
    plt.plot(time_list, Q_int_list)
    plt.legend(["I_int", "Q_int"])
    plt.subplot(2, 1, 2)
    plt.plot(time_list, I_quant_list)
    plt.plot(time_list, Q_quant_list)
    plt.legend(["I_quant", "Q_quant"])
    plt.show(block=False)

