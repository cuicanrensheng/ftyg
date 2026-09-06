[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$toolDir = "D:\ASDF\TV Live " + [char]0x65E5 + [char]0x5FD7 + [char]0x76D1 + [char]0x63A7
Set-Location $toolDir

# 1. 确保 9527 桥接（app 内部 LogServer）
$fwd = adb forward --list 2>$null
if ($fwd -match "tcp:9527") {
    Write-Output "9527 桥接已存在"
} else {
    & python tv_log_reader.py adb --local-port 9527
}

# 2. 检查/启动静态服务器 (8000)
$existing = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
if (-not $existing) {
    Start-Process python -ArgumentList "-m","http.server","8000","--directory",$toolDir -WindowStyle Hidden
    Start-Sleep -Seconds 2
    Write-Output "静态服务器已启动: http://localhost:8000"
} else {
    Write-Output "8000 端口已有服务在监听"
}

# 3. 健康检查
try {
    $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://localhost:8000/tv_log_viewer.html
    Write-Output "页面可访问: HTTP $($r.StatusCode)"
} catch {
    Write-Output "页面检查失败: $_"
}

# 4. 确认 app 内部日志服务器可达
try {
    $h = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://localhost:9527/health
    Write-Output "app 内部日志服务器: $($h.Content)"
} catch {
    Write-Output "app 内部日志服务器不可达: $_"
}
