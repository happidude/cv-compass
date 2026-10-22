param([string]$WorkerRoot=(Resolve-Path "$PSScriptRoot/../..").Path,[string]$CleanName='windows-clean')
$ErrorActionPreference='Stop'
$base=Join-Path $WorkerRoot 'build/m6-1-spike/python3148'
$clean=Join-Path $WorkerRoot ('build/m6-2-hardening/'+$CleanName)
$wheels=Join-Path $WorkerRoot 'build/m6-2-hardening/wheels-windows'
if(Test-Path -LiteralPath $clean){throw 'Clean target already exists; inspect before reuse'}
New-Item -ItemType Directory -Force $clean,$wheels | Out-Null
Get-ChildItem -LiteralPath $base -File | Where-Object Name -NE 'python314._pth' | ForEach-Object {
 Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $clean $_.Name)
}
$pth="python314.zip`n.`nLib/site-packages`n$(Join-Path $WorkerRoot 'src')`nimport site`n"
[IO.File]::WriteAllText((Join-Path $clean 'python314._pth'),$pth,[Text.UTF8Encoding]::new($false))
$taskOriginalPath=$env:PATH
try {
$env:PATH=(Join-Path $clean 'Scripts')+[IO.Path]::PathSeparator+$taskOriginalPath
& (Join-Path $WorkerRoot '.venv/Scripts/python.exe') (Join-Path $PSScriptRoot 'download_locked.py') $WorkerRoot $wheels 2>&1 | Tee-Object (Join-Path $clean 'download.log')
if($LASTEXITCODE -ne 0){throw 'Wheel download/hash verification failed'}
& (Join-Path $WorkerRoot '.venv/Scripts/python.exe') -m pip --python (Join-Path $clean 'python.exe') install --no-index --find-links $wheels --only-binary=:all: --require-hashes -r (Join-Path $WorkerRoot 'requirements.lock') -r (Join-Path $WorkerRoot 'requirements-test.lock') 2>&1 | Tee-Object (Join-Path $clean 'install.log')
if($LASTEXITCODE -ne 0){throw 'Fresh hash-locked install failed'}
& (Join-Path $clean 'python.exe') -c 'import importlib.metadata,json,sys; print(json.dumps({"python":sys.version,"path":sys.path,"packages":{d.metadata["Name"]:d.version for d in importlib.metadata.distributions()}},indent=2))' | Set-Content (Join-Path $clean 'environment.json') -Encoding utf8
& (Join-Path $WorkerRoot '.venv/Scripts/python.exe') -m pip --python (Join-Path $clean 'python.exe') check 2>&1 | Tee-Object (Join-Path $clean 'pip-check.log')
if($LASTEXITCODE -ne 0){throw 'Clean pip check failed'}

} finally { $env:PATH=$taskOriginalPath }
