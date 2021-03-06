#ifndef CALCS_HEADER
#define CALCS_HEADER
# include <math.h>
# include <stdlib.h>
# include "params.h"

float t_k(float t, struct rcv_params *params);
float eccentric_anomaly(float Mk, struct rcv_params *params);
void get_sv_pos(float t, struct sat_loc_params *loc_params, struct rcv_params *params);
#endif
