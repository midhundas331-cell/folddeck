# Folddeck 0.12.0: focused security review

## Method and scope

Direct source review and local regression/device testing, informed by Shannon's
architecture, threat-model and confirmation workflow. Shannon checkout:
`b9744f6cecc5429333873b86a6762f1d70c54fc2` from
<https://github.com/KeygraphHQ/shannon>. The reviewed prompts are under
`apps/worker/prompts/sast/capella/`.

**No autonomous Shannon scan ran.** Docker socket access is denied for the
current user and Shannon model credentials are not configured. Its standard
web/HTTP workflow is also not a complete assessment of this native Android
client and custom TLS service. This review is not a certification or an
exhaustive penetration test.

## Trust boundaries examined

- Network peer → TLS 1.3 handshake → paired token → desktop streaming/input.
  Relevant code: `host/security.py`, `host/stream.py`, Android `Secure.java`.
- Trusted laptop → bounded sharing SEI → Android foreground, permissions,
  availability and demand gate → Camera2 / AudioRecord.
- Paired phone → bounded type-8 messages → clipboard or local media subprocess.
  Subprocess arguments are fixed arrays; clipboard/media bytes go through stdin.
- Local laptop applications → PipeWire audio links / v4l2loopback capture usage
  → phone capture demand. Local consumers are trusted to request enabled inputs.
- Session-owned virtual devices/default overrides → normal disconnect cleanup.
  A user-changed default is preserved rather than overwritten during cleanup.

## Confirmed issues addressed in this change

1. **Availability previously started capture.** In v1, receiving READY with a
   microphone/camera bit called the sensor start method immediately. This did
   not meet the requested privacy policy. V2 separates availability and actual
   consumer demand. Idle webcam frames are generated black images on the laptop;
   the phone sensors remain stopped.
2. **Readiness was not tied to a configuration revision.** A delayed v1 READY
   could be applied to a later configuration after disable/re-enable. V2 tags
   CONFIG, READY, DEMAND and upstream media with a revision; stale state and
   media cannot authorize a newer configuration. Old sharing UUIDs are ignored.
3. **Capture needed a bounded loss-of-demand response.** Demand now renews every
   approximately 500 ms and expires after three seconds, checked every 250 ms
   on Android's main thread. A missing/failed observer sends no active demand.
   Foreground/focus loss, disable and disconnect stop capture locally without
   waiting for a host acknowledgement. Expiry is not a hard realtime guarantee
   if Android's main thread itself stalls.
4. **Late sensor failure callbacks could affect a replacement capture.**
   Microphone revisions and camera generations now guard queued failure handling.

## Verification

- All 22 existing JVM check programs passed, including TLS downgrade rejection,
  host-scoped credentials, parser bounds and outbound queue limits.
- Extended `SharingCheck`: idle availability does not capture; consumer start/
  stop; lease expiry; stale revision after disable; host requests cannot enable
  a disabled input; background configuration remains inactive.
- All eight host check scripts passed. These cover pairing-window concurrency,
  rejection of unknown credentials, real isolated TLS 1.3 negotiation, malformed
  and fragmented sharing packets, input release and connection takeover.
- `test_demand.py`: active/paused audio graph links, default restoration,
  preservation of manual default changes, stale config rejection and fail-closed
  observer errors.
- Real laptop probe: idle → both consumers → idle, with default restoration.
- Signed Galaxy Fold device test: permission-enabled but idle sensors; both
  consumers activate capture; real RGB and PCM data reach laptop devices;
  consumers closing releases sensors; second consumer activation works; settings
  Disable controls persist and stop capture; virtual microphone removal and both
  previous default overrides restored. Clipboard tested in both directions.
  Media samples remained in bounded memory rather than being saved.

## Remaining boundaries and limitations

- First contact is trust-on-first-use, not an out-of-band authenticated identity
  exchange. Use a trusted network for first pairing and compare fingerprints.
- A compromised paired laptop can request an enabled phone sensor while Folddeck
  is focused. The demand protocol is not proof that a benign application requested
  it. Disable controls and OS permissions remain the local authority.
- FFmpeg and Android media decoders remain native-code trust boundaries. Input
  byte/pixel limits reduce exposure; this review did not fuzz those dependencies.
- Default restoration was verified for normal disable/disconnect, not host
  SIGKILL or power loss. Startup removes stale Folddeck overrides/devices but
  cannot recover an in-memory previous explicit default after process death.
- Applications with a remembered device selection may ignore system defaults.
  Native V4L2 selection is not universally controlled by PipeWire metadata.
- No denial-of-service flood, third-party infrastructure scan or public-network
  exploitation was performed.
