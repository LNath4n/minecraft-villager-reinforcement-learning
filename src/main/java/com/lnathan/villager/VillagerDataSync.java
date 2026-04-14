package com.lnathan.villager;
//Cualquier villager modificado puede decirme si tiene hambre
public interface VillagerDataSync {
    VillagerState getVillagerState();
    void setVillagerState(VillagerState state);
}// Sirve para hablar del aldeano en el servidor