# End-to-end check of the Edge API from the host PC.
# Forwards the port to the emulator/device over adb, then exercises every endpoint
# the way OpenWebUI would.
#
#   .\scripts\smoke-test.ps1                 # emulator/device on adb, port 8080
#   .\scripts\smoke-test.ps1 -ApiKey secret
#   .\scripts\smoke-test.ps1 -BaseUrl http://192.168.0.23:8080 -NoForward   # real phone on Wi-Fi
param(
    [int]$Port = 8080,
    [string]$ApiKey = "",
    [string]$BaseUrl = "",
    [switch]$NoForward
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not $BaseUrl) { $BaseUrl = "http://127.0.0.1:$Port" }
if (-not $NoForward) {
    & $adb forward "tcp:$Port" "tcp:$Port" | Out-Null
    Write-Host "adb forward tcp:$Port -> device tcp:$Port"
}

$auth = @()
if ($ApiKey) { $auth = @("-H", "Authorization: Bearer $ApiKey") }
$failed = 0

function Step([string]$name, [scriptblock]$body) {
    Write-Host ""
    Write-Host "== $name" -ForegroundColor Cyan
    try { & $body } catch { Write-Host "FAIL: $_" -ForegroundColor Red; $script:failed++ }
}

function Invoke-Curl([string[]]$curlArgs) {
    $out = & curl.exe -sS @curlArgs
    if ($LASTEXITCODE -ne 0) { throw "curl exit $LASTEXITCODE" }
    return $out
}

function PostJson([string]$path, [string]$json, [string[]]$extra = @()) {
    # Write the body to a file: PowerShell 5.1 mangles quotes in native-command arguments.
    $tmp = New-TemporaryFile
    [IO.File]::WriteAllText($tmp, $json)
    try {
        return Invoke-Curl (@("-X", "POST", "$BaseUrl$path", "-H", "Content-Type: application/json") + $auth + $extra + @("--data-binary", "@$tmp"))
    } finally { Remove-Item $tmp }
}

Step "GET /health" {
    Invoke-Curl (@("-w", "  [HTTP %{http_code}]", "$BaseUrl/health"))
}

Step "GET /v1/models" {
    $r = (Invoke-Curl (@("$BaseUrl/v1/models") + $auth)) | ConvertFrom-Json
    if (-not $r.data) { throw "no model loaded (is the backend ready?)" }
    Write-Host "model id: $($r.data[0].id)"
}

Step "POST /v1/chat/completions (non-stream)" {
    $body = '{"model":"local","messages":[{"role":"system","content":"You are concise."},{"role":"user","content":"Say hi in five words."}],"max_tokens":64}'
    $r = (PostJson "/v1/chat/completions" $body) | ConvertFrom-Json
    if ($r.error) { throw ($r.error | ConvertTo-Json -Compress) }
    Write-Host "finish_reason: $($r.choices[0].finish_reason), completion_tokens: $($r.usage.completion_tokens)"
    Write-Host "content: $($r.choices[0].message.content)"
}

Step "POST /v1/chat/completions (stream, SSE)" {
    $body = '{"model":"local","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"Count from one to five."}]}'
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $lines = PostJson "/v1/chat/completions" $body @("-N")
    $sw.Stop()
    $data = @($lines | Where-Object { $_ -like "data: *" })
    $text = ($data | Where-Object { $_ -ne "data: [DONE]" } | ForEach-Object {
        $c = ($_.Substring(6) | ConvertFrom-Json).choices
        if ($c -and $c[0].delta.content) { $c[0].delta.content }
    }) -join ""
    Write-Host "events: $($data.Count), last: $($data[-1]), elapsed: $($sw.ElapsedMilliseconds)ms"
    Write-Host "content: $text"
    if ($data[-1] -ne "data: [DONE]") { throw "stream did not end with [DONE]" }
}

Step "max_tokens=5 -> finish_reason=length" {
    $body = '{"model":"local","messages":[{"role":"user","content":"Write a long story /long"}],"max_tokens":5}'
    $r = (PostJson "/v1/chat/completions" $body) | ConvertFrom-Json
    Write-Host "finish_reason: $($r.choices[0].finish_reason), completion_tokens: $($r.usage.completion_tokens)"
    if ($r.choices[0].finish_reason -ne "length") { throw "expected finish_reason=length" }
}

Step "Multi-turn: KV cache reuse (hit) vs edited history (miss)" {
    # A long first turn makes prefill cost visible in TTFT.
    $long = ("Here is some background context for the conversation. " * 25).Trim()
    $sys = @{ role = "system"; content = "You are a helpful assistant." }
    $u1 = @{ role = "user"; content = $long }
    $turn1 = (PostJson "/v1/chat/completions" (@{ messages = @($sys, $u1) } | ConvertTo-Json -Depth 5)) | ConvertFrom-Json
    $reply = $turn1.choices[0].message.content
    Write-Host ("turn 1: cache={0} ttft={1}ms" -f $turn1.x_edge.cache, $turn1.x_edge.ttft_ms)

    $a1 = @{ role = "assistant"; content = $reply }
    $u2 = @{ role = "user"; content = "And a follow-up question?" }
    $hit = (PostJson "/v1/chat/completions" (@{ messages = @($sys, $u1, $a1, $u2); max_tokens = 3 } | ConvertTo-Json -Depth 5)) | ConvertFrom-Json
    Write-Host ("turn 2 (same history):   cache={0} reused={1} ttft={2}ms" -f $hit.x_edge.cache, $hit.x_edge.reused_messages, $hit.x_edge.ttft_ms)

    $a1edited = @{ role = "assistant"; content = "$reply (edited)" }
    $miss = (PostJson "/v1/chat/completions" (@{ messages = @($sys, $u1, $a1edited, $u2); max_tokens = 3 } | ConvertTo-Json -Depth 5)) | ConvertFrom-Json
    Write-Host ("turn 2 (edited history): cache={0} ttft={1}ms" -f $miss.x_edge.cache, $miss.x_edge.ttft_ms)

    if ($hit.x_edge.cache -ne "hit") { throw "expected a cache hit for the unchanged history" }
    if ($miss.x_edge.cache -ne "miss") { throw "expected a cache miss for the edited history" }
    if ($hit.x_edge.ttft_ms -ge $miss.x_edge.ttft_ms) { throw "cache hit was not faster than the miss" }
}

Step "Context overflow: oldest turns are truncated" {
    $max = ((Invoke-Curl (@("$BaseUrl/debug/status"))) | ConvertFrom-Json).context.max_tokens
    # 8 turns of ~max/4 tokens each (~4 chars per token) = about twice the context window.
    $filler = ("lorem ipsum dolor sit amet " * [int]($max / 27)).Trim()
    $msgs = @(@{ role = "system"; content = "You are a helpful assistant." })
    1..4 | ForEach-Object {
        $msgs += @{ role = "user"; content = "Turn ${_}: $filler" }
        $msgs += @{ role = "assistant"; content = "Answer ${_}: $filler" }
    }
    $msgs += @{ role = "user"; content = "Summarize our conversation." }
    $r = (PostJson "/v1/chat/completions" (@{ messages = $msgs; max_tokens = 5 } | ConvertTo-Json -Depth 5)) | ConvertFrom-Json
    if ($r.error) { throw ($r.error | ConvertTo-Json -Compress) }
    $x = $r.x_edge
    Write-Host ("context {0} tok: sent {1} msgs, truncated {2}, prompt ~{3} tok, budget {4}, finish={5}" -f `
        $max, $msgs.Count, $x.truncated_messages, $x.prompt_tokens_estimate, $x.completion_budget, $r.choices[0].finish_reason)
    if ($x.truncated_messages -le 0) { throw "expected truncation" }
    if ($x.prompt_tokens_estimate -ge $max) { throw "prompt estimate does not fit the window" }
}

Step "Oversized single message -> HTTP 400 context_length_exceeded (before SSE starts)" {
    $max = ((Invoke-Curl (@("$BaseUrl/debug/status"))) | ConvertFrom-Json).context.max_tokens
    $huge = "word " * ($max * 2)
    $out = @(PostJson "/v1/chat/completions" (@{ stream = $true; messages = @(@{ role = "user"; content = $huge }) } | ConvertTo-Json -Depth 5) @("-w", "`n%{http_code}"))
    $code = $out[-1]
    $body = ($out[0..($out.Count - 2)] -join "") | ConvertFrom-Json
    Write-Host "HTTP $code, code=$($body.error.code): $($body.error.message)"
    if ($code -ne "400" -or $body.error.code -ne "context_length_exceeded") { throw "expected 400 context_length_exceeded" }
}

Step "Bad request -> 400" {
    PostJson "/v1/chat/completions" '{"messages":"nope"}' @("-w", "  [HTTP %{http_code}]")
}

Step "GET /debug/status" {
    Invoke-Curl (@("$BaseUrl/debug/status"))
}

Write-Host ""
if ($failed -eq 0) { Write-Host "ALL STEPS PASSED" -ForegroundColor Green } else { Write-Host "$failed STEP(S) FAILED" -ForegroundColor Red; exit 1 }
