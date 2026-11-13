param([Parameter(Mandatory=$true)][string]$JsonJar)
$ErrorActionPreference='Stop'
if(-not $env:JAVA_HOME){throw 'Set JAVA_HOME first.'}
$classes=Join-Path $PSScriptRoot 'build/offline-checks'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpath=(Join-Path $PSScriptRoot 'app/libs/*')+';'+(Resolve-Path -LiteralPath $JsonJar).Path
$tests=Join-Path $PSScriptRoot 'vendor/hgtv/android/core/src/test/java'
$sources=@('EpisodePreloader.java','SyncOutbox.java','DanmakuTimeline.java') | ForEach-Object {Join-Path $PSScriptRoot ('app/src/main/java/dev/local/hongguotv/'+$_)}
$sources+=@('PlaybackStreamsCheck.java','LoginAndPreloadCheck.java','FeaturesOfflineCheck.java') | ForEach-Object {Join-Path $tests $_}
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp $classpath -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Offline checks compilation failed'}
foreach($name in @('PlaybackStreamsCheck','LoginAndPreloadCheck','FeaturesOfflineCheck')){
 & "$env:JAVA_HOME/bin/java.exe" -cp ($classpath+';'+$classes) $name
 if($LASTEXITCODE -ne 0){throw "Offline checks failed: $name"}
}
