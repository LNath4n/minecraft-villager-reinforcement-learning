package com.lnathan.villager.brian;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.npc.villager.Villager;


/**
 * Registra el último evento de daño recibido por el aldeano.
 *
 * last_damage_type encoding:
 *   0.0 = sin daño reciente
 *   0.2 = caída
 *   0.4 = ahogamiento / lava
 *   0.6 = fuego
 *   0.8 = mob / proyectil
 *   1.0 = explosión (creeper, TNT)
 */
public class VillagerHurtTracker {

    private static final int HURT_MEMORY_TICKS = 60;

    private float lastDamageType   = 0.0f;
    private int   ticksSinceHurt   = Integer.MAX_VALUE;
    private float lastDamageAmount = 0.0f;

    public void onHurt(DamageSource source, float amount) {
        this.lastDamageAmount = amount;
        this.ticksSinceHurt   = 0;
        this.lastDamageType   = classifyDamage(source);
    }

    public void tick() {
        if (ticksSinceHurt < Integer.MAX_VALUE) ticksSinceHurt++;
        if (ticksSinceHurt > HURT_MEMORY_TICKS) {
            lastDamageType   = 0.0f;
            lastDamageAmount = 0.0f;
        }
    }

    /** [7] health_norm — vida actual / vida máxima */
    public static float healthNorm(Villager self) {
        return self.getHealth() / self.getMaxHealth();
    }

    /** [8] last_damage_type */
    public float lastDamageType() { return lastDamageType; }

    /** [9] was_hurt_recently */
    public float wasHurtRecently() {
        return ticksSinceHurt <= HURT_MEMORY_TICKS ? 1.0f : 0.0f;
    }

    private float classifyDamage(DamageSource source) {
        if (source.is(net.minecraft.world.damagesource.DamageTypes.FALL))               return 0.2f;
        if (source.is(net.minecraft.world.damagesource.DamageTypes.DROWN) ||
                source.is(net.minecraft.world.damagesource.DamageTypes.LAVA))               return 0.4f;
        if (source.is(net.minecraft.world.damagesource.DamageTypes.IN_FIRE)  ||
                source.is(net.minecraft.world.damagesource.DamageTypes.ON_FIRE)  ||
                source.is(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR))          return 0.6f;
        if (source.is(net.minecraft.world.damagesource.DamageTypes.EXPLOSION) ||
                source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_EXPLOSION))   return 1.0f;
        return 0.8f;
    }
}