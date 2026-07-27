/*
 * aes_cmac.h — self-contained AES-128 CMAC (RFC 4493). No external crypto lib,
 * so it builds anywhere the QNX toolchain does. Used to authenticate SecOC WiFi
 * credential messages; must match the sender's AES-CMAC byte-for-byte.
 */
#ifndef AES_CMAC_H
#define AES_CMAC_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Compute the AES-128 CMAC of msg[0..len-1] under the 16-byte key.
 * Writes the full 16-byte tag to mac; callers using truncated SecOC tags
 * compare only the leading bytes. */
void aes128_cmac(const uint8_t key[16], const uint8_t *msg, size_t len, uint8_t mac[16]);

#ifdef __cplusplus
}
#endif

#endif /* AES_CMAC_H */
