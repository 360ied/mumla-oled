/*
 * Copyright (C) 2026 Brian Zhu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.state

import com.google.protobuf.ByteString
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.net.InetAddress
import java.security.cert.X509Certificate
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.primaryConstructor

/**
 * Guards the target-architecture immutable state roots.
 *
 * Walks every class reachable from the state roots by primary-constructor
 * properties and fails on mutability. See
 * docs/tech-debt-whales/target-architecture/enforcement.md.
 */
class StateImmutabilityTest {
    private val stopClasses = setOf(
        // Object with a lifetime, not a value (reached through ClientState).
        "se.lublin.humla.client.Session",
    )

    private val leafClasses: Set<KClass<*>> = setOf(
        String::class,
        Boolean::class,
        Int::class,
        Long::class,
        Float::class,
        Double::class,
        Byte::class,
        Short::class,
        Char::class,
        Unit::class,
        ByteString::class,
        InetAddress::class,
        X509Certificate::class,
        Throwable::class,
    )

    private fun isLeaf(cls: KClass<*>): Boolean {
        if (cls in leafClasses) return true
        if (cls.javaPrimitiveType != null) return true
        if (cls.java.isEnum) return true
        // Kotlin unsigned + JVM boxed primitives.
        return when (cls.qualifiedName) {
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Short", "kotlin.Int",
            "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.Char",
            "kotlin.String", "kotlin.Unit",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float",
            "java.lang.Double", "java.lang.Character", "java.lang.String",
            "com.google.protobuf.ByteString",
            "java.net.InetAddress",
            "java.security.cert.X509Certificate",
            "java.lang.Throwable",
            "se.lublin.humla.net.HandshakeFailure",
            -> true
            else -> false
        }
    }

    private fun sealedImplementations(cls: KClass<*>): List<KClass<*>> = when (cls) {
        SessionEvent::class -> listOf(
            SessionEvent.TextMessageReceived::class,
            SessionEvent.UserJoinedServer::class,
            SessionEvent.UserLeft::class,
            SessionEvent.UserMoved::class,
            SessionEvent.RecordingChanged::class,
            SessionEvent.SelfMuteStateChanged::class,
            SessionEvent.UserSelfMuteChanged::class,
            SessionEvent.WelcomeText::class,
            SessionEvent.ConnectionWarning::class,
            SessionEvent.TextMessageSent::class,
            SessionEvent.PermissionDenied::class,
        )
        else -> cls.java.classes.mapNotNull { nested ->
            // Fallback: nested classes are the only other sealed children in state.
            runCatching { nested.kotlin }.getOrNull()
        }
    }

    private fun classLabel(cls: KClass<*>): String =
        cls.qualifiedName ?: cls.toString()

    @Test
    fun stateRootsAreDeeplyImmutable() {
        val roots: List<KClass<*>> = listOf(
            SessionState::class,
            ServerState::class,
            ServerInfo::class,
            ServerSettingsInfo::class,
            ChannelInfo::class,
            UserInfo::class,
            LocalState::class,
            WhisperTarget::class,
            SyncPhase::class,
            VoiceTargetMode::class,
            SessionEvent::class,
        )
        val visited = mutableSetOf<String>()
        val failures = mutableListOf<String>()
        for (root in roots) {
            walk(root, root, visited, failures)
        }
        if (failures.isNotEmpty()) {
            fail("mutable state reachable:\n" + failures.joinToString("\n"))
        }
        assertTrue("expected to visit state types", visited.isNotEmpty())
    }

    private fun walk(
        cls: KClass<*>,
        root: KClass<*>,
        visited: MutableSet<String>,
        failures: MutableList<String>,
    ) {
        val name = classLabel(cls)
        if (name in stopClasses) return
        if (isLeaf(cls)) return
        if (!visited.add(name)) return

        // Sealed: walk every implementation (listed explicitly to avoid
        // the full-reflect sealedSubclasses lookup).
        if (cls.isSealed) {
            for (sub in sealedImplementations(cls)) {
                walk(sub, root, visited, failures)
            }
            return
        }

        // Objects (data object) carry no instance state.
        if (cls.objectInstance != null) return

        val primary = cls.primaryConstructor
        if (primary == null) {
            // Enums already returned as leaves; anything else without a
            // primary constructor is not an immutable value type.
            failures.add("$name (from ${classLabel(root)}): no primary constructor")
            return
        }

        val members = cls.members.filterIsInstance<KProperty1<Any, *>>()
            .associateBy { it.name }
        for (param in primary.parameters) {
            val propName = param.name ?: continue
            @Suppress("UNCHECKED_CAST")
            val prop = members[propName] as? KProperty1<Any, *> ?: continue
            if (prop is KMutableProperty1<*, *>) {
                failures.add("$name.$propName: is a var")
                continue
            }
            checkType(prop.returnType.classifier, "$name.$propName", root, visited, failures)
            // Recurse into generic arguments (e.g. PersistentMap values).
            for (arg in prop.returnType.arguments) {
                val argClassifier = arg.type?.classifier
                if (argClassifier != null) {
                    checkType(argClassifier, "$name.$propName<...>", root, visited, failures)
                }
            }
        }
    }

    private fun checkType(
        classifier: Any?,
        where: String,
        root: KClass<*>,
        visited: MutableSet<String>,
        failures: MutableList<String>,
    ) {
        if (classifier !is KClass<*>) return
        val qname = classifier.qualifiedName ?: return
        // Forbidden: java.util collections.
        if (qname.startsWith("java.util.")) {
            failures.add("$where: java.util collection $qname")
            return
        }
        // Forbidden: Kotlin mutable collections.
        if (qname == "kotlin.collections.MutableList" ||
            qname == "kotlin.collections.MutableSet" ||
            qname == "kotlin.collections.MutableMap" ||
            qname == "kotlin.collections.MutableCollection" ||
            qname == "kotlin.collections.MutableIterable"
        ) {
            failures.add("$where: mutable collection $qname")
            return
        }
        // Forbidden: arrays (ByteString is the blob type, not ByteArray).
        if (qname == "kotlin.Array" || qname.startsWith("kotlin.") && qname.endsWith("Array")) {
            failures.add("$where: array type $qname")
            return
        }
        // Forbidden: non-persistent immutable interfaces (permit mutable impls).
        if (qname == "kotlinx.collections.immutable.ImmutableList" ||
            qname == "kotlinx.collections.immutable.ImmutableSet" ||
            qname == "kotlinx.collections.immutable.ImmutableMap" ||
            qname == "kotlinx.collections.immutable.ImmutableCollection"
        ) {
            failures.add("$where: non-persistent immutable $qname (use Persistent*)")
            return
        }
        // Allowed containers: Persistent* (recurse into args at the call site,
        // plus walk the container itself is a leaf for our purposes).
        if (qname == "kotlinx.collections.immutable.PersistentList" ||
            qname == "kotlinx.collections.immutable.PersistentSet" ||
            qname == "kotlinx.collections.immutable.PersistentMap" ||
            qname == "kotlinx.collections.immutable.PersistentCollection"
        ) {
            return
        }
        // Allowed read-only Kotlin collections (events carry List<Int>).
        if (qname == "kotlin.collections.List" ||
            qname == "kotlin.collections.Set" ||
            qname == "kotlin.collections.Map" ||
            qname == "kotlin.collections.Collection" ||
            qname == "kotlin.collections.Iterable"
        ) {
            return
        }
        if (isLeaf(classifier)) return
        val cname = classLabel(classifier)
        if (cname in stopClasses) return
        // Must be a value type: data class/object, enum, or sealed.
        val isValue = classifier.isData || classifier.objectInstance != null ||
            classifier.java.isEnum || classifier.isSealed
        if (!isValue) {
            failures.add("$where: $cname is not a data class/object, enum, or sealed type")
            return
        }
        walk(classifier, root, visited, failures)
    }
}
