# dfr_lkm provenance

The `dfr_lkm-android*.ko` files are the upstream prebuilt kernel modules from
GhostSam (https://github.com/MocLG/GhostSam, app assets
`payloads/dirtyfrag/dfr_lkm-<kmi>.ko`), Apache-2.0. They follow the public
DirtyFrag technique; see GhostSam's NOTICE.md for its provenance statement.

They are selected only on Samsung devices (KMI-matched exactly like the
temproot modules, same eight KMIs). No modification was made to them.
