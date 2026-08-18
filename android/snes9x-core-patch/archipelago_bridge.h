#ifndef SNES9X_ARCHIPELAGO_BRIDGE_H
#define SNES9X_ARCHIPELAGO_BRIDGE_H

#include <stdbool.h>

void APBridgeInit(void);
void APBridgeDeinit(void);
void APBridgePoll(bool rom_loaded);
void APBridgeNotifyReset(void);

#endif
