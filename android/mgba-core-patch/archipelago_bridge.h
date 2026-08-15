/*
 * Archipelago loopback bridge for mGBA's libretro frontend.
 *
 * This file is intended to live in mGBA's src/platform/libretro directory.
 * It is kept in the Archipelago tree so the Android integration can be
 * reviewed and versioned alongside its companion application.
 */
#ifndef AP_MGBA_BRIDGE_H
#define AP_MGBA_BRIDGE_H

#include <mgba-util/socket.h>

struct mCore;

#define AP_MGBA_BRIDGE_PORT 43056
#define AP_MGBA_MESSAGE_MAX 512

struct APBridge {
	Socket listener;
	Socket client;
	uint8_t input[8192];
	size_t inputSize;
	char message[AP_MGBA_MESSAGE_MAX];
	size_t messageLength;
	bool messagePending;
};

bool APBridgeInit(struct APBridge* bridge, uint16_t port);
void APBridgeDeinit(struct APBridge* bridge);

/* Call from retro_run only. This keeps every core memory access on the
 * emulator thread and avoids handing raw memory pointers to another thread. */
void APBridgePoll(struct APBridge* bridge, struct mCore* core);

/* Copies and clears the next companion notification. Call from retro_run so
 * the returned text can be passed directly to the libretro environment. */
bool APBridgeTakeMessage(struct APBridge* bridge, char* message, size_t capacity);

#endif
