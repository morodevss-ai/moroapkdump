# WolfDumper APK v3.0

Android app with full UI — dark theme, live console, progress bar, auto root check, auto export.

---

## Build

1. Push this repo to GitHub
2. Actions tab → latest run → download **WolfDumper-APK**
3. Install the APK on your phone
4. Grant root when asked + grant storage permission

---

## Requirements (on phone)

| What | Where | Notes |
|---|---|---|
| `wolfdumper` binary | `/data/local/tmp/wolfdumper` | Built from WolfDumperV3 repo |
| `dotnet` | Termux: `pkg install dotnet` | For fast parsing |
| `Il2CppDumper.dll` + dlls | `/data/local/tmp/Il2CppDumper/` | From Il2CppDumper_OxcyShop repo |
| `il2cpp_dump.py` | `/data/local/tmp/il2cpp_dump.py` | Python fallback |

---

## UI Features

- **Root badge** — green if rooted, red if not
- **dotnet / Il2CppDumper / Target** status cards
- **Free Fire / FF MAX** selector buttons
- **Live console** — colored log output (green OK, yellow warn, red error)
- **Progress bar** — stage name + percentage
- **FULL DUMP** button — disk copy → metadata → Il2CppDumper → offsets
- **EXPORT** button — opens output folder in file manager

---

## Output (auto-generated)

```
/sdcard/WolfDumper/com.dts.freefireth_DATE_TIME/
  (0xBASE)libil2cpp.so
  global-metadata.dat
  Il2CppDumpOut/
    dump.cs
    DummyDll/
  offsets.txt       ← all offsets with class names
  offsets.json      ← machine readable
  initBase.txt      ← InitBase = 0xXXXX  (copy-paste ready)
```

`initBase.txt` contains the library base address — paste directly into your cheat.
