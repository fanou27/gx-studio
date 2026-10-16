@echo off
rem ===========================================================================
rem  GX Studio - construire-outils.bat (Windows)
rem  (c) 2026 ALONSO Stephane [Fanou]
rem
rem  Recupere rasm et Caprice32 dans leurs depots git, applique a Caprice32 le
rem  correctif GX Studio (caprice32\gxstudio-caprice32.patch) et compile les
rem  deux avec MSYS2 (gcc MinGW 64 bits). Le travail est fait par
rem  construire-outils.sh, lance dans l'environnement MINGW64 de MSYS2.
rem
rem  Usage : construire-outils.bat [DOSSIER] [options du script .sh]
rem    DOSSIER : ou ranger les sources et les programmes
rem              (defaut : le dossier "outils" a cote de ce fichier)
rem    options : -r dernier (derniere version de rasm), -j N
rem  MSYS2 est cherche dans C:\msys64 (ou dans la variable MSYS2_ROOT) ; s'il
rem  manque, le script propose de l'installer avec winget.
rem ===========================================================================
setlocal EnableExtensions
set "ICI=%~dp0"
if not defined MSYS2_ROOT set "MSYS2_ROOT=C:\msys64"
set "BASH=%MSYS2_ROOT%\usr\bin\bash.exe"

rem ---- dossier de destination (premier argument s'il ne commence pas par -)
set "DEST=%ICI%outils"
set "ARG1=%~1"
if not "%ARG1%"=="" if not "%ARG1:~0,1%"=="-" (
    set "DEST=%~f1"
    shift
)
set "OPTS="
:opts
if "%~1"=="" goto optsfin
set "OPTS=%OPTS% %~1"
shift
goto opts
:optsfin

if not exist "%ICI%construire-outils.sh" (
    echo ERREUR : construire-outils.sh doit etre dans le meme dossier que ce fichier.
    goto fin_erreur
)

rem ---- MSYS2
if exist "%BASH%" goto msys_ok
echo MSYS2 est introuvable dans %MSYS2_ROOT%.
echo Il fournit git, make et le compilateur gcc necessaires a la compilation.
where winget >nul 2>nul
if errorlevel 1 goto msys_manuel
choice /C ON /M "Installer MSYS2 maintenant avec winget (O = oui, N = non)"
if errorlevel 2 goto msys_manuel
winget install --id MSYS2.MSYS2 -e --accept-source-agreements --accept-package-agreements
if exist "%BASH%" goto msys_neuf
echo ERREUR : MSYS2 ne s'est pas installe dans %MSYS2_ROOT%.
echo Si vous l'avez installe ailleurs : set MSYS2_ROOT=chemin, puis relancez.
goto fin_erreur

:msys_manuel
echo Installez MSYS2 depuis https://www.msys2.org (dossier par defaut C:\msys64),
echo ou indiquez son dossier :  set MSYS2_ROOT=D:\msys64  puis relancez ce fichier.
goto fin_erreur

:msys_neuf
echo.
echo == Premier lancement de MSYS2 : creation du profil
set "MSYSTEM=MSYS"
"%BASH%" -lc "true"

:msys_ok
set "MSYSTEM=MINGW64"
set "CHERE_INVOKING=1"

echo.
echo == Mise a jour de MSYS2 (pacman -Syu)
rem Deux passes : la premiere peut ne mettre a jour que le coeur de MSYS2.
"%BASH%" -lc "pacman -Syu --noconfirm"
"%BASH%" -lc "pacman -Syu --noconfirm"

echo.
echo == Construction de rasm et Caprice32 dans %DEST%
if not exist "%DEST%" mkdir "%DEST%"
rem Le dossier passe par la variable GX_DEST (pas de guillemets imbriques
rem pour bash) ; CHERE_INVOKING garde bash dans le dossier du script.
set "GX_DEST=%DEST%"
cd /d "%ICI%"
"%BASH%" -lc "./construire-outils.sh -d %OPTS%"
if errorlevel 1 goto fin_erreur

echo.
echo Termine. Chemins a donner a GX Studio (Outils ^> Reglages) :
echo    rasm      : %DEST%\rasm\rasm.exe
echo    Caprice32 : %DEST%\caprice32\cap32.exe
goto fin

:fin_erreur
echo.
echo La construction n'a pas abouti (voir les messages ci-dessus).
endlocal
pause
exit /b 1

:fin
endlocal
pause
exit /b 0
