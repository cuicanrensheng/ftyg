# Build mini libc++_shared.so for BOTH arm64-v8a and armeabi-v7a
$ErrorActionPreference = 'Stop'
$ndk = 'C:\Users\16937\Android\ndk\27.0.12077973'
$bin = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin"
$sysroot = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\sysroot"

$common = @(
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
'_ZdaPv','_ZdlPv',
'__cxa_guard_acquire','__cxa_guard_release','__cxa_pure_virtual'
)

$symsArm64 = $common + @('_Znam','_Znwm','__emutls_get_address')
$symsV7a = $common + @(
'_ZNSt6__ndk16localeC1EPKc',
'_ZNSt6__ndk16localeC1ERKS0_',
'_ZNSt6__ndk16localeaSERKS0_',
'_ZNSt6__ndk17codecvtIwc9mbstate_tE2idE',
'_Znaj','_Znwj',
'__aeabi_idiv','__aeabi_idivmod','__aeabi_ldivmod',
'__aeabi_uidiv','__aeabi_uidivmod','__aeabi_uldivmod'
)

function Write-Map($map, $syms) {
  $lines = @('{', '  global:')
  foreach ($s in $syms) { $lines += "    $s;" }
  $lines += @('  local: *;', '};')
  $lines | Set-Content $map -Encoding ascii
}

function Build-Mini($triple, $libname, $abi, $syms, $extraLibs, $outName) {
  $libdir = "$sysroot\usr\lib\$libname"
  Write-Host "=== Building $abi ($($syms.Count) syms) ==="
  $map = "_mini_$abi.map"
  Write-Map $map $syms
  $argsList = @()
  $argsList += "--target=$triple"
  $argsList += '-shared', '-fPIC', '-fvisibility=hidden', '-O2'
  foreach ($s in $syms) { $argsList += "-Wl,--undefined=$s" }
  $argsList += '-Wl,--gc-sections'
  $argsList += "-Wl,--version-script=$map"
  $argsList += '-Wl,-soname,libc++_shared.so'
  $out = "_so_analyze\$outName"
  $argsList += "-o", $out
  $argsList += '-Wl,--start-group'
  $argsList += "$libdir\libc++_static.a", "$libdir\libc++abi.a"
  foreach ($l in $extraLibs) { $argsList += "$l" }
  $argsList += '-Wl,--end-group'
  $argsList += '-lc', '-lm', '-ldl', '-llog'
  & "$bin\clang++.exe" @argsList 2>&1 | ForEach-Object { Write-Host $_ }
  if ($LASTEXITCODE -ne 0) { Write-Host "LINK FAILED $abi"; return $false }
  $size = [math]::Round((Get-Item $out).Length/1KB)
  Write-Host "  $outName = ${size} KB"
  $dyn = & "$bin\llvm-nm.exe" -D --defined-only $out
  $missing = @()
  foreach ($s in $syms) {
    if ($dyn -notcontains "T $s" -and $dyn -notcontains "W $s" -and $dyn -notcontains "B $s" -and $dyn -notcontains "D $s" -and $dyn -notcontains "t $s" -and $dyn -notcontains "w $s") { $missing += $s }
  }
  if ($missing.Count -eq 0) { Write-Host "  ALL symbols exported OK" } else { Write-Host "  MISSING: $($missing -join ', ')" }
  return $true
}

$clangrt = 'C:\Users\16937\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\18\lib\linux'
$btFix = "$PWD\_so_analyze\builtins_aeabi.a"
Build-Mini 'aarch64-linux-android21' 'aarch64-linux-android' 'arm64' $symsArm64 @("$clangrt\libclang_rt.builtins-aarch64-android.a") 'mini_libc++_shared_arm64.so'
Build-Mini 'armv7a-linux-androideabi21' 'arm-linux-androideabi' 'v7a' $symsV7a @($btFix) 'mini_libc++_shared_v7a.so'
