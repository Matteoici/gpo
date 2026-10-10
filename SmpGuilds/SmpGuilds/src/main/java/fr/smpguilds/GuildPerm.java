package fr.smpguilds;

/** Actions qu'un rang peut avoir le droit de faire (configurable dans config.yml > roles). */
public enum GuildPerm {
    INVITE, KICK, PROMOTE, APPLICATIONS, CLAIM, UNCLAIM, SETHOME, HOME,
    BANK_DEPOSIT, BANK_WITHDRAW, VAULT, BUILD, EDIT, ALLY, WAR, LOG
}
