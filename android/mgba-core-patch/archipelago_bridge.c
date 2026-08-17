/*
 * Archipelago loopback bridge for mGBA's libretro frontend.
 *
 * Protocol: a big-endian 20-byte header followed by an optional payload.
 *   u32 magic ("APB1"), u16 type, u16 status, u32 id, u32 address, u32 length
 *
 * The server binds only to 127.0.0.1. READ, WRITE, and GUARD operate on the
 * emulated system bus, SAVEDATA_READ snapshots the core's battery-save backing
 * store independently of the cartridge's current SRAM mapping, and ROM_READ
 * reads the loaded Game Boy cartridge by physical file offset. The maximum
 * transfer is kept intentionally small so polling it from retro_run never
 * introduces a frame hitch. The Android companion may batch adjacent requests.
 */
#include "archipelago_bridge.h"

#include <mgba/core/core.h>

#include <stdlib.h>
#include <string.h>

#define APB_MAGIC 0x41504231u
#define APB_HEADER_SIZE 20u
#define APB_MAX_PAYLOAD 4096u
#define APB_PROTOCOL_VERSION 6u

enum APBType {
	APB_HELLO = 1,
	APB_PING = 2,
	APB_READ = 3,
	APB_WRITE = 4,
	APB_GUARD = 5,
	APB_ROM_SHA1 = 6,
	APB_MESSAGE = 7,
	APB_GUARDED_WRITE = 8,
	APB_BATCH_READ = 9,
	APB_GUARDED_READ = 10,
	APB_GUARDED_WRITES = 11,
	APB_SAVEDATA_READ = 12,
	APB_ROM_READ = 13,
};

enum APBStatus {
	APB_OK = 0,
	APB_BAD_REQUEST = 1,
	APB_TOO_LARGE = 2,
	APB_GUARD_FAILED = 3,
	APB_UNSUPPORTED = 4,
};

struct APBHeader {
	uint32_t magic;
	uint16_t type;
	uint16_t status;
	uint32_t id;
	uint32_t address;
	uint32_t length;
};

static uint16_t _readU16(const uint8_t* p) {
	return (uint16_t) ((p[0] << 8) | p[1]);
}

static uint32_t _readU32(const uint8_t* p) {
	return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | p[3];
}

static void _writeU16(uint8_t* p, uint16_t value) {
	p[0] = value >> 8;
	p[1] = value;
}

static void _writeU32(uint8_t* p, uint32_t value) {
	p[0] = value >> 24;
	p[1] = value >> 16;
	p[2] = value >> 8;
	p[3] = value;
}

static void _decodeHeader(struct APBHeader* header, const uint8_t* data) {
	header->magic = _readU32(data);
	header->type = _readU16(data + 4);
	header->status = _readU16(data + 6);
	header->id = _readU32(data + 8);
	header->address = _readU32(data + 12);
	header->length = _readU32(data + 16);
}

static bool _sendResponse(struct APBridge* bridge, const struct APBHeader* request, uint16_t status,
		const uint8_t* payload, uint32_t length) {
	uint8_t response[APB_HEADER_SIZE + APB_MAX_PAYLOAD];
	_writeU32(response, APB_MAGIC);
	_writeU16(response + 4, request->type);
	_writeU16(response + 6, status);
	_writeU32(response + 8, request->id);
	_writeU32(response + 12, request->address);
	_writeU32(response + 16, length);
	if (length) {
		memcpy(response + APB_HEADER_SIZE, payload, length);
	}
	return SocketSend(bridge->client, response, APB_HEADER_SIZE + length) == (ssize_t) (APB_HEADER_SIZE + length);
}

static bool _processRequest(struct APBridge* bridge, struct mCore* core, const struct APBHeader* request,
		const uint8_t* payload) {
	uint8_t data[APB_MAX_PAYLOAD];
	uint32_t i, readLength;
	if (request->magic != APB_MAGIC || request->length > APB_MAX_PAYLOAD) {
		return _sendResponse(bridge, request, request->length > APB_MAX_PAYLOAD ? APB_TOO_LARGE : APB_BAD_REQUEST, NULL, 0);
	}

	switch (request->type) {
	case APB_HELLO:
		/* Current protocol version and the active mPlatform identifier. */
		data[0] = APB_PROTOCOL_VERSION;
		data[1] = (uint8_t) core->platform(core);
		return _sendResponse(bridge, request, APB_OK, data, 2);
	case APB_PING:
		return _sendResponse(bridge, request, APB_OK, NULL, 0);
	case APB_ROM_SHA1:
		core->checksum(core, data, mCHECKSUM_SHA1);
		return _sendResponse(bridge, request, APB_OK, data, 20);
	case APB_READ:
		/* READ uses a four-byte requested-length payload; the response carries
		 * exactly that many memory bytes. */
		if (request->length != 4) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		readLength = _readU32(payload);
		if (readLength > APB_MAX_PAYLOAD) {
			return _sendResponse(bridge, request, APB_TOO_LARGE, NULL, 0);
		}
		for (i = 0; i < readLength; ++i) {
			data[i] = core->busRead8(core, request->address + i);
		}
		return _sendResponse(bridge, request, APB_OK, data, readLength);
	case APB_SAVEDATA_READ: {
		/* SAVEDATA_READ uses a byte offset in the header and a four-byte
		 * requested-length payload. savedataClone bypasses the emulated MBC's
		 * RAM-enable state, which makes battery-save reads reliable while a Game
		 * Boy title has its external SRAM unmapped. */
		void* savedata = NULL;
		size_t savedataSize;
		if (request->length != 4) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		readLength = _readU32(payload);
		if (readLength > APB_MAX_PAYLOAD) {
			return _sendResponse(bridge, request, APB_TOO_LARGE, NULL, 0);
		}
		if (!core->savedataClone) {
			return _sendResponse(bridge, request, APB_UNSUPPORTED, NULL, 0);
		}
		savedataSize = core->savedataClone(core, &savedata);
		if (!savedata || request->address > savedataSize ||
				readLength > savedataSize - request->address) {
			free(savedata);
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		memcpy(data, (const uint8_t*) savedata + request->address, readLength);
		free(savedata);
		return _sendResponse(bridge, request, APB_OK, data, readLength);
	}
	case APB_ROM_READ: {
		/* BizHawk exposes a flat ROM domain even for banked Game Boy carts.
		 * Locate mGBA's cart0 block and read its physical backing buffer so AP
		 * clients can inspect headers and authentication data above 64 KiB. */
		const struct mCoreMemoryBlock* blocks = NULL;
		const uint8_t* rom = NULL;
		size_t blockCount;
		size_t block;
		size_t romSize = 0;
		if (request->length != 4) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		readLength = _readU32(payload);
		if (readLength > APB_MAX_PAYLOAD) {
			return _sendResponse(bridge, request, APB_TOO_LARGE, NULL, 0);
		}
		if (!core->listMemoryBlocks || !core->getMemoryBlock) {
			return _sendResponse(bridge, request, APB_UNSUPPORTED, NULL, 0);
		}
		blockCount = core->listMemoryBlocks(core, &blocks);
		for (block = 0; block < blockCount; ++block) {
			if (blocks[block].internalName && !strcmp(blocks[block].internalName, "cart0")) {
				rom = core->getMemoryBlock(core, blocks[block].id, &romSize);
				break;
			}
		}
		if (!rom) {
			return _sendResponse(bridge, request, APB_UNSUPPORTED, NULL, 0);
		}
		if (request->address > romSize || readLength > romSize - request->address) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		memcpy(data, rom + request->address, readLength);
		return _sendResponse(bridge, request, APB_OK, data, readLength);
	}
	case APB_GUARD:
		for (i = 0; i < request->length; ++i) {
			if (core->busRead8(core, request->address + i) != payload[i]) {
				return _sendResponse(bridge, request, APB_GUARD_FAILED, NULL, 0);
			}
		}
		return _sendResponse(bridge, request, APB_OK, NULL, 0);
	case APB_WRITE:
		for (i = 0; i < request->length; ++i) {
			core->busWrite8(core, request->address + i, payload[i]);
		}
		return _sendResponse(bridge, request, APB_OK, NULL, 0);
	case APB_MESSAGE:
		if (!request->length) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		if (request->length >= AP_MGBA_MESSAGE_MAX) {
			return _sendResponse(bridge, request, APB_TOO_LARGE, NULL, 0);
		}
		memcpy(bridge->message, payload, request->length);
		bridge->message[request->length] = '\0';
		bridge->messageLength = request->length;
		bridge->messagePending = true;
		return _sendResponse(bridge, request, APB_OK, NULL, 0);
	case APB_GUARDED_WRITE: {
		/* Payload: u16 guard count, repeated (u32 address, u16 length,
		 * expected bytes), followed by u16 write length and write bytes.
		 * Parsing and all comparisons finish before the first mutation. */
		uint32_t position = 0;
		uint16_t guardCount;
		uint16_t guard;
		uint16_t writeLength;
		if (request->length < 4) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		guardCount = _readU16(payload);
		position = 2;
		if (!guardCount) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		for (guard = 0; guard < guardCount; ++guard) {
			uint32_t guardAddress;
			uint16_t guardLength;
			if (position + 6 > request->length) {
				return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
			}
			guardAddress = _readU32(payload + position);
			guardLength = _readU16(payload + position + 4);
			position += 6;
			if (position + guardLength > request->length) {
				return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
			}
			for (i = 0; i < guardLength; ++i) {
				if (core->busRead8(core, guardAddress + i) != payload[position + i]) {
					return _sendResponse(bridge, request, APB_GUARD_FAILED, NULL, 0);
				}
			}
			position += guardLength;
		}
		if (position + 2 > request->length) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		writeLength = _readU16(payload + position);
		position += 2;
		if (position + writeLength != request->length) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		for (i = 0; i < writeLength; ++i) {
			core->busWrite8(core, request->address + i, payload[position + i]);
		}
		return _sendResponse(bridge, request, APB_OK, NULL, 0);
	}
	case APB_BATCH_READ: {
		/* Payload: u16 count, then repeated u32 address + u16 length. */
		uint32_t position = 2;
		uint32_t outputPosition = 0;
		uint16_t count, range;
		if (request->length < 2) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		count = _readU16(payload);
		if (!count || request->length != 2u + (uint32_t) count * 6u) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		for (range = 0; range < count; ++range) {
			uint32_t address = _readU32(payload + position);
			uint16_t length = _readU16(payload + position + 4);
			position += 6;
			if (outputPosition + length > APB_MAX_PAYLOAD) {
				return _sendResponse(bridge, request, APB_TOO_LARGE, NULL, 0);
			}
			for (i = 0; i < length; ++i) {
				data[outputPosition + i] = core->busRead8(core, address + i);
			}
			outputPosition += length;
		}
		return _sendResponse(bridge, request, APB_OK, data, outputPosition);
	}
	case APB_GUARDED_READ:
	case APB_GUARDED_WRITES: {
		/* Both payloads start with guards. The remaining section is either
		 * read descriptors or write descriptors. The entire request and every
		 * guard are validated before any write is performed. */
		uint32_t position = 2;
		uint32_t operationPosition;
		uint32_t outputPosition = 0;
		uint16_t guardCount, guard, operationCount, operation;
		if (request->length < 4) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		guardCount = _readU16(payload);
		for (guard = 0; guard < guardCount; ++guard) {
			uint16_t length;
			if (position + 6 > request->length) {
				return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
			}
			length = _readU16(payload + position + 4);
			position += 6;
			if (position + length > request->length) {
				return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
			}
			position += length;
		}
		if (position + 2 > request->length) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		operationCount = _readU16(payload + position);
		position += 2;
		operationPosition = position;
		if (!operationCount) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		for (operation = 0; operation < operationCount; ++operation) {
			uint16_t length;
			if (position + 6 > request->length) {
				return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
			}
			length = _readU16(payload + position + 4);
			position += 6;
			if (request->type == APB_GUARDED_WRITES) {
				if (position + length > request->length) {
					return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
				}
				position += length;
			} else if (outputPosition + length > APB_MAX_PAYLOAD) {
				return _sendResponse(bridge, request, APB_TOO_LARGE, NULL, 0);
			} else {
				outputPosition += length;
			}
		}
		if (position != request->length) {
			return _sendResponse(bridge, request, APB_BAD_REQUEST, NULL, 0);
		}
		position = 2;
		for (guard = 0; guard < guardCount; ++guard) {
			uint32_t address = _readU32(payload + position);
			uint16_t length = _readU16(payload + position + 4);
			position += 6;
			for (i = 0; i < length; ++i) {
				if (core->busRead8(core, address + i) != payload[position + i]) {
					return _sendResponse(bridge, request, APB_GUARD_FAILED, NULL, 0);
				}
			}
			position += length;
		}
		position = operationPosition;
		outputPosition = 0;
		for (operation = 0; operation < operationCount; ++operation) {
			uint32_t address = _readU32(payload + position);
			uint16_t length = _readU16(payload + position + 4);
			position += 6;
			for (i = 0; i < length; ++i) {
				if (request->type == APB_GUARDED_READ) {
					data[outputPosition + i] = core->busRead8(core, address + i);
				} else {
					core->busWrite8(core, address + i, payload[position + i]);
				}
			}
			if (request->type == APB_GUARDED_READ) {
				outputPosition += length;
			} else {
				position += length;
			}
		}
		return _sendResponse(bridge, request, APB_OK,
			request->type == APB_GUARDED_READ ? data : NULL, outputPosition);
	}
	default:
		return _sendResponse(bridge, request, APB_UNSUPPORTED, NULL, 0);
	}
}

bool APBridgeInit(struct APBridge* bridge, uint16_t port) {
	struct Address loopback = { .version = IPV4, .ipv4 = 0x7F000001 };
	memset(bridge, 0, sizeof(*bridge));
	bridge->listener = INVALID_SOCKET;
	bridge->client = INVALID_SOCKET;
	SocketSubsystemInit();
	bridge->listener = SocketOpenTCP(port, &loopback);
	if (SOCKET_FAILED(bridge->listener) || SocketListen(bridge->listener, 4)) {
		APBridgeDeinit(bridge);
		return false;
	}
	if (!SocketSetBlocking(bridge->listener, false)) {
		APBridgeDeinit(bridge);
		return false;
	}
	return true;
}

void APBridgeDeinit(struct APBridge* bridge) {
	if (!SOCKET_FAILED(bridge->client)) {
		SocketClose(bridge->client);
	}
	if (!SOCKET_FAILED(bridge->listener)) {
		SocketClose(bridge->listener);
	}
	bridge->client = INVALID_SOCKET;
	bridge->listener = INVALID_SOCKET;
	bridge->inputSize = 0;
	bridge->messageLength = 0;
	bridge->messagePending = false;
}

/*
 * RetroArch stops calling retro_run while its activity is paused. During that
 * time the Android companion may time out, close its socket, and establish a
 * replacement connection which sits in the listener queue. The old socket
 * cannot be observed as closed until polling resumes, so always prefer the
 * newest queued client instead of making it wait behind stale state.
 */
static void _acceptPendingClients(struct APBridge* bridge) {
	Socket pending;
	Socket newest = INVALID_SOCKET;
	size_t accepted = 0;
	while (accepted < 8) {
		pending = SocketAccept(bridge->listener, NULL);
		if (SOCKET_FAILED(pending)) {
			break;
		}
		++accepted;
		if (!SocketSetBlocking(pending, false)) {
			SocketClose(pending);
			continue;
		}
		if (!SOCKET_FAILED(newest)) {
			SocketClose(newest);
		}
		newest = pending;
	}

	if (SOCKET_FAILED(newest)) {
		return;
	}
	if (!SOCKET_FAILED(bridge->client)) {
		SocketClose(bridge->client);
	}
	bridge->client = newest;
	bridge->inputSize = 0;
}

void APBridgePoll(struct APBridge* bridge, struct mCore* core) {
	ssize_t received;
	if (!core || SOCKET_FAILED(bridge->listener)) {
		return;
	}
	_acceptPendingClients(bridge);
	if (SOCKET_FAILED(bridge->client)) {
		return;
	}

	received = SocketRecv(bridge->client, bridge->input + bridge->inputSize, sizeof(bridge->input) - bridge->inputSize);
	if (received == 0 || (received < 0 && !SocketWouldBlock())) {
		SocketClose(bridge->client);
		bridge->client = INVALID_SOCKET;
		bridge->inputSize = 0;
		return;
	}
	if (received > 0) {
		bridge->inputSize += (size_t) received;
	}

	while (bridge->inputSize >= APB_HEADER_SIZE) {
		struct APBHeader request;
		_decodeHeader(&request, bridge->input);
		if (request.length > APB_MAX_PAYLOAD || bridge->inputSize < APB_HEADER_SIZE + request.length) {
			break;
		}
		if (!_processRequest(bridge, core, &request, bridge->input + APB_HEADER_SIZE)) {
			SocketClose(bridge->client);
			bridge->client = INVALID_SOCKET;
			bridge->inputSize = 0;
			return;
		}
		memmove(bridge->input, bridge->input + APB_HEADER_SIZE + request.length,
			bridge->inputSize - APB_HEADER_SIZE - request.length);
		bridge->inputSize -= APB_HEADER_SIZE + request.length;
	}
}

bool APBridgeTakeMessage(struct APBridge* bridge, char* message, size_t capacity) {
	if (!bridge->messagePending || !message || capacity <= bridge->messageLength) {
		return false;
	}
	memcpy(message, bridge->message, bridge->messageLength + 1);
	bridge->messageLength = 0;
	bridge->messagePending = false;
	return true;
}
