param([Parameter(Mandatory=$true)][string]$KotlinCompilerClasspath,[Parameter(Mandatory=$true)][string]$JsonJar)
$ErrorActionPreference='Stop'
if(-not $env:JAVA_HOME){throw 'Set JAVA_HOME first.'}
$libraryRoot=Join-Path $PSScriptRoot 'app/libs'
$runtime=Join-Path $libraryRoot 'kotlin-stdlib-1.9.24.jar'
$dependencies=@(Get-ChildItem -LiteralPath $libraryRoot -Filter '*.jar' | Where-Object Name -ne 'hgtv-core.jar' | ForEach-Object FullName)
$dependencies+=$JsonJar
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'vendor/hgtv/android/core/src/main/kotlin') -Recurse -Filter '*.kt' | ForEach-Object FullName)
& "$env:JAVA_HOME/bin/java.exe" -Xmx1024m -cp ($KotlinCompilerClasspath+';'+$runtime) org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -classpath ($dependencies -join ';') -d (Join-Path $libraryRoot 'hgtv-core.jar') $sources
if($LASTEXITCODE -ne 0){throw 'Content core compilation failed'}
