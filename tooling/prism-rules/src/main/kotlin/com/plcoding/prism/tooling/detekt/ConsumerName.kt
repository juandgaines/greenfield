package com.plcoding.prism.tooling.detekt

/**
 * The guard for rule parameters that name a declaration in the CONSUMER's
 * repository — a palette object, a theme composable, an extended-token holder.
 *
 * WHY A BLANK CHECK WAS NOT ENOUGH, MEASURED RATHER THAN IMAGINED. PRISM's own
 * convention is that a repository lacking the shape switches the group off and
 * gives the names the inert value `'none'`. Two shipped documents promised that
 * turning a rule back on without a real name would "fail loudly with the rule's
 * own message". It did not: the rules guarded with `isNotBlank()`, and `none`
 * is not blank.
 *
 * An install shipped `paletteObject: 'none'` on two rules that were
 * `active: true`. Each compared a receiver against the literal string "none",
 * never matched, and reported success over five files that violated them. The
 * placeholder resolved, the YAML was valid, the rule name was declared, the
 * config validated — every check in the system passed.
 *
 * THIS IS THE SECOND NET, NOT THE FIRST. `render.py` refuses the pairing at
 * install, where the switch and the name are in one dict. That cannot see a
 * hand edit to `detekt.yml` afterwards, and this can.
 *
 * A rule that throws is loud, which is the point. The alternative — quietly
 * deactivating itself — is the false green wearing a different hat.
 */
internal val CONSUMER_NAME_SENTINELS =
    setOf("", "none", "n/a", "na", "todo", "tbd", "unknown", "changeme", "xxx")

/**
 * Returns [value], or throws naming the key, the switch and both legal answers.
 *
 * @param value the configured name
 * @param rule the rule reporting the problem, so the message is self-locating
 * @param key the config key under that rule
 * @param switch the group switch whose `false` is the other legal answer
 */
internal fun requireConsumerName(
    value: String,
    rule: String,
    key: String,
    switch: String,
): String {
    check(value.trim().lowercase() !in CONSUMER_NAME_SENTINELS) {
        "$rule has '$key' set to \"$value\", which is a placeholder rather than a " +
            "name. It must be the name of a declaration in YOUR repository, because " +
            "a name that matches nothing makes this rule report success over the " +
            "code it was pointed at.\n" +
            "  Either set '$key' under $rule in detekt.yml to a real declaration,\n" +
            "  or set every rule guarded by $switch to 'active: false' — which is " +
            "the correct answer when your repository genuinely has no such " +
            "declaration.\n" +
            "  See 'Changing things' in the PRISM documentation folder, under\n" +
            "  'Adding or changing a group switch'."
    }
    return value
}
