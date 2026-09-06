# Build mini libc++_shared.so keeping only 38 symbols marsstn needs
$ErrorActionPreference = 'Stop'
$ndk = 'C:\Users\16937\Android\ndk\27.0.12077973'
$bin = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin"
$sysroot = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot"
$libdir = "$sysroot\usr\lib\aarch64-linux-android"

$syms = @(
'_ZNKSt13runtime_error4whatEv',
'_ZNKSt6__ndk119__shared_weak_count13__get_deleterERKSt9type_info',
'_ZNKSt6__ndk16locale9use_facetERNS0_2idE',
'_ZNKSt6__ndk18ios_base6getlocEv',
'_ZNKSt9exception4whatEv',
'_ZNSt13runtime_errorC1EPKc',
'_ZNSt13runtime_errorC2EPKc',
'_ZNSt13runtime_errorC2ERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE',
'_ZNSt13runtime_errorD1Ev',
'_ZNSt13runtime_errorD2Ev',
'_ZNSt6__ndk112__rs_defaultD1Ev',
'_ZNSt6__ndk112__rs_defaultclEv',
'_ZNSt6__ndk119__shared_weak_count14__release_weakEv',
'_ZNSt6__ndk119__shared_weak_countD2Ev',
'_ZNSt6__ndk15ctypeIcE2idE',
'_ZNSt6__ndk16localeC1Ev',
'_ZNSt6__ndk16localeD1Ev',
'_ZNSt6__ndk17num_putIcNS_19ostreambuf_iteratorIcNS_11char_traitsIcEEEEE2idE',
'_ZNSt6__ndk18__rs_getEv',
'_ZNSt6__ndk18ios_base4initEPv',
'_ZNSt6__ndk18ios_base5clearEj',
'_ZNSt6__ndk18ios_baseD2Ev',
'_ZNSt9exceptionD2Ev',
'_ZSt18uncaught_exceptionv',
'_ZTINSt6__ndk119__shared_weak_countE',
'_ZTINSt6__ndk18ios_baseE',
'_ZTISt13runtime_error',
'_ZTISt9exception',
'_ZTVN10__cxxabiv117__class_type_infoE',
'_ZTVN10__cxxabiv120__si_class_type_infoE',
'_ZTVN10__cxxabiv121__vmi_class_type_infoE',
'_ZdaPv','_ZdlPv','_Znam','_Znwm',
'__cxa_guard_acquire','__cxa_guard_release','__cxa_pure_virtual'
)

# version script: export only these 38, rest local
$map = '_mini_cxx.map'
$mapLines = @('{', '  global:')
foreach ($s in $syms) { $mapLines += "    $s;" }
$mapLines += @('  local: *;', '};')
$mapLines | Set-Content $map -Encoding ascii

$argsList = @()
$argsList += '--target=aarch64-linux-android21'
$argsList += '-shared'
$argsList += '-fPIC'
$argsList += '-fvisibility=hidden'
$argsList += '-O2'
foreach ($s in $syms) { $argsList += "-Wl,--undefined=$s" }
$argsList += '-Wl,--gc-sections'
$argsList += "-Wl,--version-script=$map"
$argsList += '-Wl,-soname,libc++_shared.so'
$out = '_so_analyze\mini_libc++_shared.so'
$argsList += "-o", $out
$argsList += '-Wl,--start-group'
$argsList += "$libdir\libc++_static.a", "$libdir\libc++abi.a"
$argsList += '-Wl,--end-group'
$argsList += '-lc', '-lm', '-ldl', '-llog'

& "$bin\clang++.exe" @argsList 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { Write-Host '=== LINK FAILED ==='; exit 1 }

Write-Host '=== SIZE ==='
Get-Item $out | ForEach-Object { Write-Host ("{0}  {1} KB" -f $_.Name, [math]::Round($_.Length/1KB)) }

Write-Host '=== EXPORT CHECK ==='
$dyn = & "$bin\llvm-nm.exe" -D --defined-only $out
$missing = @()
foreach ($s in $syms) {
  if ($dyn -notmatch [regex]::Escape(" $s")) { $missing += $s }
}
if ($missing.Count -eq 0) { Write-Host 'ALL 38 symbols exported OK' } else { Write-Host ("MISSING: {0}" -f ($missing -join ', ')) }

Write-Host '=== SONAME ==='
& "$bin\llvm-readelf.exe" -d $out | Select-String 'SONAME'
