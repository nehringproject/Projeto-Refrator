"""API-compatible Android fallback for the optional Rust-backed ``fastuuid`` accelerator.

LiteLLM uses this package through ``uuid4``. Python's standard library implementation obtains
randomness from ``os.urandom``, which Chaquopy maps to Android's secure operating-system RNG.
"""

from uuid import (  # noqa: F401
    NAMESPACE_DNS,
    NAMESPACE_OID,
    NAMESPACE_URL,
    NAMESPACE_X500,
    RESERVED_FUTURE,
    RESERVED_MICROSOFT,
    RESERVED_NCS,
    RFC_4122,
    UUID,
    getnode,
    uuid1,
    uuid3,
    uuid4,
    uuid5,
)

__all__ = [
    "UUID",
    "getnode",
    "uuid1",
    "uuid3",
    "uuid4",
    "uuid5",
    "NAMESPACE_DNS",
    "NAMESPACE_OID",
    "NAMESPACE_URL",
    "NAMESPACE_X500",
    "RESERVED_NCS",
    "RFC_4122",
    "RESERVED_MICROSOFT",
    "RESERVED_FUTURE",
]
