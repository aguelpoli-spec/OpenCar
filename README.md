# OpenCar 🚗

**Launcher y reproductor de música de código abierto para pantallas Android de auto** (probado en una pantalla de 7″, 1024×600, horizontal).
Funciona sin internet una vez descargado el mapa de tu región.

> English summary: open-source Android head-unit launcher + music player. Pick your car brand/model (logos and photos
> as backgrounds), download offline maps for **any** region (OpenStreetMap), offline turn-by-turn routing (BRouter),
> offline "Hey <brand>" voice assistant (Vosk), fuel log, maintenance reminders, clock/speed/media widgets.
> The UI is currently **Spanish only** — translations are very welcome (see CONTRIBUTING.md).

**Probado** en la pantalla Android de un Nissan Rogue 2013 (7″, 1024×600). Otros autos y pantallas deberían funcionar, pero aún no tenemos reportes: ¡cuéntanos en Issues!

## Capturas
> Capturas generadas con datos de ejemplo (sin información personal), simulando una pantalla de 7″ 1024×600.

| Inicio | Mi auto |
|---|---|
| ![Inicio](docs/screenshots/inicio.png) | ![Mi auto](docs/screenshots/mi-auto.png) |

| Mapas sin internet | Música |
|---|---|
| ![Mapas](docs/screenshots/mapas.png) | ![Música](docs/screenshots/musica.png) |

**Mapa sin internet** (dibujado con el motor real de la app, a distintos niveles de zoom; datos © colaboradores de OpenStreetMap)

| Ciudad | Avenidas | Calles |
|---|---|---|
| ![Mapa ciudad](docs/screenshots/mapa-ciudad.png) | ![Mapa avenidas](docs/screenshots/mapa-avenidas.png) | ![Mapa calles](docs/screenshots/mapa-calles.png) |

Modo noche (tema propio, calles claras sobre fondo oscuro):

![Mapa de noche](docs/screenshots/mapa-noche.png)

| Viaje y telemetría | Diagnóstico y ajustes |
|---|---|
| ![Viaje](docs/screenshots/viaje.png) | ![Diagnóstico](docs/screenshots/diagnostico.png) |

| Asistente CarPlay | Ecualizador y mezcla DJ |
|---|---|
| ![CarPlay](docs/screenshots/carplay.png) | ![Ecualizador](docs/screenshots/ecualizador.png) |

### Modos de interfaz
| Ejecutivo | Pink Rouse |
|---|---|
| ![Ejecutivo](docs/screenshots/modo-ejecutivo.png) | ![Pink Rouse](docs/screenshots/modo-pink-rouse.png) |

| Viaje | Sport |
|---|---|
| ![Modo viaje](docs/screenshots/modo-viaje.png) | ![Sport](docs/screenshots/modo-sport.png) |

### Modo manejo
![Modo manejo](docs/screenshots/modo-manejo.png)

### Más funciones del Launcher
| Calendario con feriados | Asistente de voz |
|---|---|
| ![Calendario](docs/screenshots/calendario.png) | ![Asistente](docs/screenshots/asistente-voz.png) |

| Elegir marca de tu auto (≈390 logos) | Mi música |
|---|---|
| ![Marcas](docs/screenshots/selector-marca.png) | ![Biblioteca](docs/screenshots/biblioteca.png) |

### OpenCar Music
| Reproductor | Biblioteca |
|---|---|
| ![Reproductor](docs/screenshots/music-reproductor.png) | ![Biblioteca](docs/screenshots/music-biblioteca.png) |

| Búsqueda | Ajustes |
|---|---|
| ![Buscar](docs/screenshots/music-buscar.png) | ![Ajustes](docs/screenshots/music-ajustes.png) |

| Panel de la app (historial, carátulas, letras) | Carátulas automáticas |
|---|---|
| ![Panel](docs/screenshots/music-app-inicio.png) | ![Carátulas](docs/screenshots/music-app-caratulas.png) |

## Compatibilidad con Android
| App | Android mínimo | Nota |
|---|---|---|
| OpenCar Launcher | **8.0 (API 26)** | compilada para API 34 |
| OpenCar Music | **10 (API 29)** | compilada para API 34 |

No funcionan en Android 7 o anterior. Los APK de la sección Releases están firmados con la llave de depuración: para uso propio, compila y firma con tu propia llave.

Dos apps en un solo repositorio:

| App | Carpeta | Qué hace |
|---|---|---|
| **OpenCar Launcher** | `launcher/` | Pantalla de inicio del auto: reloj, velocidad, música, mapa sin internet, navegación por voz, gasolina, mantenimiento, documentos, asistente de voz |
| **OpenCar Music** | `music/` | Reproductor, historial de escuchas, carátulas, letras y música libre |

## Funciones principales
- **Tu auto**: eliges marca (≈390 logos incluidos) y modelo (lista de Wikidata, del más antiguo al más nuevo) y puedes descargar una foto libre como fondo o subir la tuya.
- **Mapas de cualquier país**: elige continente → país → región; se descarga el mapa (Mapsforge) y los datos de rutas (BRouter) de esa zona.
- **Lugares**: Casa, Trabajo, Pareja, Familia y Taller, que guardas tú. Gasolineras cercanas leídas del propio mapa.
- **Asistente de voz sin internet**: «Hey <marca de tu auto>», con voz del sistema o con tus propias grabaciones (`res/raw/voz_*.mp3`).
- Gasolina (consumo y costo en tu moneda), recordatorios de mantenimiento, documentos, calendario con feriados de tu país.

## Luces del auto y resplandor de faros

El fondo de pantalla muestra el brillo de los faros cuando las luces del auto están encendidas. Para saberlo, la radio debe avisar a Android del estado de las luces; no todas lo hacen.

1. Toca el widget de luces → **Ajustes y detección de luces**.
2. Con las luces apagadas pulsa «1 · Tomar lectura»; enciéndelas y pulsa «2 · Tomar lectura»; luego «3 · Comparar» y elige el ajuste que cambió (los primeros suelen ser de luces).
3. Toca el widget y marca cómo están las luces: si la señal sale al revés, se corrige sola.

Si no aparece ningún cambio, activa en los ajustes de tu radio las opciones de luces/faros/brillo automático y repite. Si tu radio no entrega el dato, usa el toque en el widget para marcar tú las luces. Nunca se adivina por la hora.

## Compilar
Requisitos: JDK 17, Android SDK (API 34). En cada carpeta (`launcher/` y `music/`):
```
./gradlew testDebugUnitTest      # pruebas
./gradlew assembleDebug          # APK de prueba en app/build/outputs/apk/debug/
```
Para un APK instalable de "release" firma tú mismo con tu propia llave (**nunca la subas al repositorio**).

## Primer uso
1. Instala los dos APK y abre **OpenCar Launcher**; dale los permisos que pida (ubicación, notificaciones, micrófono si quieres el asistente).
2. Toca el nombre del auto → **Marca y modelo**.
3. Abre **Mapas**, toca el botón de región (país → zona) y luego **Descargar ahora**. Los mapas pesan desde unos MB hasta cientos de MB: usa Wi-Fi.
4. Ajustes → **Lugares** para guardar Casa/Trabajo, etc.

## Licencias
Código: **Apache 2.0** (`LICENSE`). Componentes y datos de terceros: ver `NOTICE`. Los logos son marcas registradas de sus dueños.
Los datos de los mapas son © OpenStreetMap contributors (ODbL).
