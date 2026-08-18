/*
 * Reset-stable, loopback-only SNI memory bridge for the SNES9x libretro core.
 *
 * Requests are accepted and executed only from retro_run(), so emulator memory
 * never crosses threads. The listener survives retro_reset(); PING reports a
 * monotonically increasing reset generation which lets the companion replay
 * the standard SNI detach/attach lifecycle after a soft reset.
 */
#include "archipelago_bridge.h"

#include "../snes9x.h"
#include "../memmap.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define APB_MAGIC 0x41504231u
#define APB_HEADER_SIZE 20u
#define APB_MAX_PAYLOAD 4096u
#define APB_PROTOCOL_VERSION 1u
#define APB_PLATFORM_SNES 3u
#define APB_PORT 43057

enum APBType {
    APB_HELLO = 1,
    APB_PING = 2,
    APB_READ = 3,
    APB_WRITE = 4,
};

enum APBStatus {
    APB_OK = 0,
    APB_BAD_REQUEST = 1,
    APB_TOO_LARGE = 2,
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

static int listener_fd = -1;
static int client_fd = -1;
static uint8_t input_buffer[APB_HEADER_SIZE + APB_MAX_PAYLOAD];
static size_t input_size = 0;
static uint32_t reset_generation = 0;
static time_t last_bind_attempt = 0;

static uint16_t read_u16(const uint8_t *p)
{
    return (uint16_t)(((uint16_t)p[0] << 8) | p[1]);
}

static uint32_t read_u32(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | p[3];
}

static void write_u16(uint8_t *p, uint16_t value)
{
    p[0] = (uint8_t)(value >> 8);
    p[1] = (uint8_t)value;
}

static void write_u32(uint8_t *p, uint32_t value)
{
    p[0] = (uint8_t)(value >> 24);
    p[1] = (uint8_t)(value >> 16);
    p[2] = (uint8_t)(value >> 8);
    p[3] = (uint8_t)value;
}

static void close_client(void)
{
    if (client_fd >= 0)
        close(client_fd);
    client_fd = -1;
    input_size = 0;
}

static void close_listener(void)
{
    close_client();
    if (listener_fd >= 0)
        close(listener_fd);
    listener_fd = -1;
}

static bool set_nonblocking(int fd)
{
    int flags = fcntl(fd, F_GETFL, 0);
    return flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

static bool open_listener(void)
{
    int fd;
    int reuse = 1;
    struct sockaddr_in address;

    last_bind_attempt = time(NULL);
    fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0)
        return false;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons(APB_PORT);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0 ||
        listen(fd, 4) != 0 || !set_nonblocking(fd)) {
        close(fd);
        return false;
    }
    listener_fd = fd;
    return true;
}

static void ensure_listener(void)
{
    time_t now;
    if (listener_fd >= 0)
        return;
    now = time(NULL);
    if (now != last_bind_attempt)
        open_listener();
}

static void accept_newest_client(void)
{
    int newest = -1;
    for (unsigned accepted = 0; accepted < 8; ++accepted) {
        int pending = accept(listener_fd, NULL, NULL);
        if (pending < 0)
            break;
        if (!set_nonblocking(pending)) {
            close(pending);
            continue;
        }
        if (newest >= 0)
            close(newest);
        newest = pending;
    }
    if (newest >= 0) {
        close_client();
        client_fd = newest;
    }
}

static bool send_all(const uint8_t *data, size_t length)
{
    size_t sent = 0;
    while (sent < length) {
        ssize_t result = send(client_fd, data + sent, length - sent, MSG_NOSIGNAL);
        if (result <= 0)
            return false;
        sent += (size_t)result;
    }
    return true;
}

static bool send_response(const APBHeader &request, uint16_t status,
                          const uint8_t *payload, uint32_t length)
{
    uint8_t response[APB_HEADER_SIZE + APB_MAX_PAYLOAD];
    write_u32(response, APB_MAGIC);
    write_u16(response + 4, request.type);
    write_u16(response + 6, status);
    write_u32(response + 8, request.id);
    write_u32(response + 12, request.address);
    write_u32(response + 16, length);
    if (length)
        memcpy(response + APB_HEADER_SIZE, payload, length);
    return send_all(response, APB_HEADER_SIZE + length);
}

/* SNI/FX Pak Pro virtual domains, independent of the cartridge mapper. */
static bool memory_byte(uint32_t address, bool write, uint8_t *value,
                        bool rom_loaded)
{
    if (!rom_loaded)
        return false;

    if (address < 0xE00000u) {
        if (address >= Memory.CalculatedSize)
            return false;
        if (write)
            Memory.ROM[address] = *value;
        else
            *value = Memory.ROM[address];
        return true;
    }

    if (address < 0xF00000u) {
        if (!Memory.SRAMSize || !Memory.SRAM)
            return false;
        uint32_t offset = (address - 0xE00000u) & 0x7ffffu;
        offset &= Memory.SRAMMask;
        if (write)
            Memory.SRAM[offset] = *value;
        else
            *value = Memory.SRAM[offset];
        return true;
    }

    if (address >= 0xF50000u && address < 0xF70000u) {
        uint32_t offset = address - 0xF50000u;
        if (write)
            Memory.RAM[offset] = *value;
        else
            *value = Memory.RAM[offset];
        return true;
    }

    return false;
}

static bool validate_range(uint32_t address, uint32_t length, bool rom_loaded)
{
    uint8_t ignored = 0;
    if (length == 0 || address + length < address)
        return false;
    for (uint32_t i = 0; i < length; ++i) {
        if (!memory_byte(address + i, false, &ignored, rom_loaded))
            return false;
    }
    return true;
}

static bool process_request(const APBHeader &request, const uint8_t *payload,
                            bool rom_loaded)
{
    uint8_t data[APB_MAX_PAYLOAD];
    uint32_t length;

    switch (request.type) {
    case APB_HELLO:
        data[0] = APB_PROTOCOL_VERSION;
        data[1] = APB_PLATFORM_SNES;
        write_u32(data + 2, reset_generation);
        return send_response(request, APB_OK, data, 6);
    case APB_PING:
        write_u32(data, reset_generation);
        return send_response(request, APB_OK, data, 4);
    case APB_READ:
        if (request.length != 4)
            return send_response(request, APB_BAD_REQUEST, NULL, 0);
        length = read_u32(payload);
        if (length > APB_MAX_PAYLOAD)
            return send_response(request, APB_TOO_LARGE, NULL, 0);
        if (!validate_range(request.address, length, rom_loaded))
            return send_response(request, APB_BAD_REQUEST, NULL, 0);
        for (uint32_t i = 0; i < length; ++i)
            memory_byte(request.address + i, false, &data[i], rom_loaded);
        return send_response(request, APB_OK, data, length);
    case APB_WRITE:
        if (request.length > APB_MAX_PAYLOAD)
            return send_response(request, APB_TOO_LARGE, NULL, 0);
        if (!validate_range(request.address, request.length, rom_loaded))
            return send_response(request, APB_BAD_REQUEST, NULL, 0);
        for (uint32_t i = 0; i < request.length; ++i) {
            uint8_t value = payload[i];
            memory_byte(request.address + i, true, &value, rom_loaded);
        }
        return send_response(request, APB_OK, NULL, 0);
    default:
        return send_response(request, APB_UNSUPPORTED, NULL, 0);
    }
}

void APBridgeInit(void)
{
    close_listener();
    last_bind_attempt = 0;
    open_listener();
}

void APBridgeDeinit(void)
{
    close_listener();
}

void APBridgeNotifyReset(void)
{
    ++reset_generation;
}

void APBridgePoll(bool rom_loaded)
{
    ensure_listener();
    if (listener_fd < 0)
        return;
    accept_newest_client();
    if (client_fd < 0)
        return;

    ssize_t received = recv(client_fd, input_buffer + input_size,
                            sizeof(input_buffer) - input_size, 0);
    if (received == 0 || (received < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) {
        close_client();
        return;
    }
    if (received > 0)
        input_size += (size_t)received;

    while (input_size >= APB_HEADER_SIZE) {
        APBHeader request;
        request.magic = read_u32(input_buffer);
        request.type = read_u16(input_buffer + 4);
        request.status = read_u16(input_buffer + 6);
        request.id = read_u32(input_buffer + 8);
        request.address = read_u32(input_buffer + 12);
        request.length = read_u32(input_buffer + 16);
        if (request.magic != APB_MAGIC || request.length > APB_MAX_PAYLOAD) {
            close_client();
            return;
        }
        size_t frame_size = APB_HEADER_SIZE + request.length;
        if (input_size < frame_size)
            break;
        if (!process_request(request, input_buffer + APB_HEADER_SIZE, rom_loaded)) {
            close_client();
            return;
        }
        memmove(input_buffer, input_buffer + frame_size, input_size - frame_size);
        input_size -= frame_size;
    }
}
