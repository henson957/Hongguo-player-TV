$ErrorActionPreference='Stop'
if(-not $env:JAVA_HOME -or -not $env:ANDROID_HOME){throw 'Set JAVA_HOME (JDK 17) and ANDROID_HOME (Android SDK 35) first.'}
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$projectRoot=$PSScriptRoot
New-Item -ItemType Directory -Path (Join-Path $projectRoot 'signing') -Force | Out-Null
$keyPath=Join-Path $projectRoot 'signing/test-build.jks'
if(-not(Test-Path -LiteralPath $keyPath)){
 & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $keyPath -storepass android -keypass android -alias hongguo-tv-test -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Hongguo TV Local Test'
 if($LASTEXITCODE -ne 0){throw 'Key generation failed'}
}
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Encoding ascii -Value ('sdk.dir='+$env:ANDROID_HOME.Replace('\','/'))
& (Join-Path $projectRoot 'gradlew.bat') -p $projectRoot --no-daemon :app:assembleDebug
if($LASTEXITCODE -ne 0){throw 'Build failed'}

