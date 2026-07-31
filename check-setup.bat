@echo off
setlocal

echo [MangaLens] Verificando ambiente de desenvolvimento...
echo.

:: 1. Verificando Java
echo [1/3] Verificando Java...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERRO] Java nao encontrado no PATH. Instale o JDK 17.
) else (
    for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
        echo Java detectado: %%g
    )
)

:: 2. Verificando .env
echo.
echo [2/3] Verificando arquivo .env...
if not exist ".env" (
    echo [AVISO] Arquivo .env nao encontrado.
    echo         Copie o .env.example para .env e adicione sua GEMINI_API_KEY.
) else (
    echo Arquivo .env presente.
)

:: 3. Verificando Variaveis de Ambiente do Android
echo.
echo [3/3] Verificando variaveis de ambiente Android...
if "%ANDROID_HOME%"=="" (
    echo [AVISO] ANDROID_HOME nao definida. Certifique-se de que o Android Studio a configurou ou defina manualmente.
) else (
    echo ANDROID_HOME: %ANDROID_HOME%
)

if not "%ANDROID_PREFS_ROOT%"=="" (
    echo [ALERTA] ANDROID_PREFS_ROOT detectada: %ANDROID_PREFS_ROOT%
    echo          ISSO PODE CAUSAR ERROS DE BUILD!
    echo          Remova-a e use apenas ANDROID_USER_HOME.
)

if "%ANDROID_USER_HOME%"=="" (
    echo [AVISO] ANDROID_USER_HOME nao definida. Recomendado para evitar conflitos no Windows.
) else (
    echo ANDROID_USER_HOME: %ANDROID_USER_HOME%
)

echo.
echo [Pronto] Se houver alertas acima, verifique o README.md para solucoes.
echo.
pause
