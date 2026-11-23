# Modelo de voz TTS (Sherpa-ONNX / Piper en español)

Esta carpeta debe contener el paquete de voz descargado de los releases oficiales de k2-fsa/sherpa-onnx (etiqueta `tts-models`).

## Archivos requeridos (colocarlos directamente aquí)

```
app/src/main/assets/tts-model/
├── es_ES-davefx-medium.onnx          # Modelo neuronal VITS (narrador audiolibro, español de España)
├── tokens.txt                        # Tokens fonéticos (alias de es_ES-davefx-medium.onnx.json)
└── espeak-ng-data/                   # Carpeta: convierte texto+puntuación a fonemas con entonación
```

## Descarga

1. Ve a https://github.com/k2-fsa/sherpa-onnx/releases
2. Sección "Assets" del release deseado (tag `tts-models`).
3. Descarga el paquete del modelo `vits-piper-es_ES-davefx-medium` (o `vits-piper-es_MX-claude-high` para español neutro/latino).
4. Descomprímelo y copia el contenido (`.onnx`, `tokens.txt` / `.onnx.json`, carpeta `espeak-ng-data/`) en esta carpeta.

> Nota: los archivos `.onnx` y la carpeta `espeak-ng-data/` son binarios grandes (30-60 MB) y están ignorados por git (`.gitignore`). No se suben al repositorio; cada entorno los descarga e instala localmente.

## Alternativa: almacenamiento interno

Si no quieres empaquetar el modelo en el APK, cópialo la primera vez que se abre la app a `context.filesDir/tts-model/` y pasa esa ruta a `SherpaOnnxTtsEngine`.