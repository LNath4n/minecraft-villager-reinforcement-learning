# Villager AI Mod

Mod para Minecraft que dota a los aldeanos de un sistema de inteligencia artificial basado en **aprendizaje por refuerzo (DQN)** y árboles de comportamiento. El objetivo es que los aldeanos tomen decisiones dinámicas (huir, dormir, buscar comida, explorar) en lugar de seguir rutinas estáticas.

> **Estado: en pausa.** Minecraft Vanilla ya implementa campamentos, y al ser un proyecto personal no puedo mantener el ritmo de los cambios en las clases de Mojang. Actualmente el enfoque es profundizar y optimizar las mecánicas ya establecidas (misiones, IA) antes de agregar mecánicas nuevas.

## Descripción

Este proyecto reemplaza la IA vanilla de los aldeanos por un sistema híbrido:

- **Behavior Trees** para decisiones estructuradas (Selector/Sequence).
- **Deep Q-Network (DQN)** para aprendizaje adaptativo en tiempo real.
- **Sistema de misiones** que los aldeanos pueden generar y completar.

## Características

- **VillagerBrain**: cerebro individual por aldeano, con persistencia de Q-table.
- **Árbol de comportamiento**: nodos `FleeNode`, `HungerNode`, `DQNNode`, `SleepNode`, entre otros.
- **Sistema de misiones**: tipos `COLLECT_ITEM`, `KILL`, `REACH_LOCATION`, construidas con Builder pattern.
- **Interfaces in-game**: `JournalScreen` y `MapScreen` para seguir el progreso.
- **VillageRegistry**: gestión centralizada de aldeas y sus aldeanos.
- **Generación procedural** de campamentos.
- **Optimización LOD**: ajuste de tick-rate según distancia/relevancia, para no penalizar el rendimiento.

## Tecnologías

| Componente         | Tecnología                          |
|--------------------|--------------------------------------|
| Framework del mod  | Fabric + Mixin                       |
| Lenguaje del mod   | Java 25                              |
| Mappings           | Mojang                               |
| Versión objetivo   | Minecraft 1.21.x                     |
| Aprendizaje        | DQN (Deep Q-Network), explorando DL4J|

## Setup

Para instrucciones de configuración, revisa la [documentación de Fabric](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)

## Contribuciones

Por ahora es un proyecto personal, pero issues y sugerencias son bienvenidos.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
