[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$toolDir = "D:\ASDF\TV Live " + [char]0x65E5 + [char]0x5FD7 + [char]0x76D1 + [char]0x63A7
Set-Location $toolDir
Write-Output "===== noise 噪音源分析 ====="
& python tv_log_reader.py noise
Write-Output ""
Write-Output "===== logs --dedup 聚合视图 ====="
& python tv_log_reader.py logs --dedup --limit 5