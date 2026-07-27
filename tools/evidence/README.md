# Independent evidence verification

Verify the hashes and internal consistency of an exported recovery bundle:

```powershell
python .\verify_bundle.py <bundle-directory>
```

When the untouched session directory has also been pulled from the phone,
verify those original bytes too:

```powershell
python .\verify_bundle.py <bundle-directory> `
  --source-session <source-session-directory>
```

If a recovered IMU file is present, the verifier reads it through the official
`mcap` Python package. `VALID` proves artifact integrity and report
consistency; it does not claim that the filmed task or visual quality was good.

Verify the hash-chained QA telemetry separately:

```powershell
python .\verify_telemetry.py `
  <source-session-directory>\probe-telemetry.jsonl
```

A process kill may leave one incomplete final line. The verifier ignores only
that torn tail and reports its exact byte count; any malformed complete line,
sequence gap or hash mismatch is an error.

For a normally finalized untouched session, one command checks the WAL, the
telemetry-to-WAL anchor, official-reader MCAP compatibility, file hashes and
the MP4 through `ffprobe` when it is installed:

```powershell
python .\verify_session.py <source-session-directory>
```
