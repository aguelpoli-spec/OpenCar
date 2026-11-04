# Guía paso a paso: publicar OpenCar en GitHub

> ⚠️ **No publiques tu repositorio personal**: su historial de git guarda tus datos. Esta carpeta (`OpenCar`) es una copia limpia, **sin historial**, y es la que se sube.

## 0. Antes de subir: revisión final (5 min)
1. Abre la carpeta y busca (Ctrl+Shift+F en VS Code / Android Studio) estas palabras: tu nombre, el de tu pareja, tu apellido, tu correo, tu placa, teléfonos, tu ciudad/barrio. Debe dar **0 resultados**.
2. Confirma que NO existen archivos `*.keystore`, `*.jks`, `local.properties`, `*.apk` (el `.gitignore` ya los excluye).

## 1. Instalar git y crear tu cuenta (si falta)
- Git: https://git-scm.com/downloads · Cuenta: https://github.com/signup
- Configura el nombre/correo que **será público** en los commits. Para no exponer tu correo real, en GitHub → Settings → Emails activa *Keep my email addresses private* y usa el correo `ID+usuario@users.noreply.github.com`:
```
git config --global user.name "TuUsuarioGitHub"
git config --global user.email "ID+TuUsuario@users.noreply.github.com"
```

## 2. Crear el repositorio vacío en GitHub
1. https://github.com/new → nombre: `OpenCar` (o el que quieras).
2. Descripción: *Launcher y reproductor Android de código abierto para pantallas de auto*.
3. **Public**. **No** marques README, .gitignore ni license (ya vienen en la carpeta).
4. *Create repository*.

## 3. Subir la carpeta (git nuevo, sin historial)
En una terminal, dentro de la carpeta `OpenCar`:
```
git init -b main
git add .
git status          # revisa que no aparezca ningún .keystore, .apk ni local.properties
git commit -m "Primera versión de OpenCar (Apache 2.0)"
git remote add origin https://github.com/TuUsuarioGitHub/OpenCar.git
git push -u origin main
```
Si pide contraseña, usa el inicio de sesión del navegador (Git Credential Manager) o un *Personal Access Token*.

## 4. Dejar el repo presentable
- En *About* (engranaje): descripción, temas `android`, `android-auto`, `head-unit`, `launcher`, `openstreetmap`, `offline-maps`, `kotlin`.
- Verás la licencia Apache-2.0 detectada automáticamente.
- Sube 2–3 capturas de pantalla (sin tu casa, placa ni nombre: usa el modo "sin lugares" o datos de ejemplo).
- **Releases** → *Draft a new release* → sube el APK firmado con TU llave (nunca la llave). Etiqueta `v1.0.0`.
- *Settings → Security*: activa *Private vulnerability reporting* y *Dependabot alerts*.

## 5. Si algo personal se coló
No basta con borrar el archivo en un commit nuevo: queda en el historial. Como el repo es nuevo, lo más simple es borrar el repositorio en GitHub (Settings → Danger Zone), borrar la carpeta `.git`, corregir y repetir el paso 3. Si ya alguien lo clonó, cambia lo expuesto (p. ej. no se puede "des-publicar" una dirección).

## 6. Mantenimiento
- Responde issues y revisa pull requests (`CONTRIBUTING.md`).
- Para versiones nuevas: `git add -A && git commit -m "..." && git push`.
