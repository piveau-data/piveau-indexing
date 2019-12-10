package io.piveau.indexing

import io.piveau.json.putIfNotNull
import io.piveau.rdf.*
import io.piveau.vocabularies.*
import io.piveau.vocabularies.vocabulary.EDP
import io.piveau.vocabularies.vocabulary.LOCN
import io.vertx.core.json.JsonObject
import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.rdf.model.*
import org.apache.jena.vocabulary.*

operator fun Statement.component1(): Resource = subject
operator fun Statement.component2(): Property = predicate
operator fun Statement.component3(): RDFNode = `object`

object StandardVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any? = null
    override fun visitLiteral(literal: Literal): Any? = when (literal.value) {
        is BaseDatatype.TypedValue -> (literal.value as BaseDatatype.TypedValue).lexicalValue
        else -> literal.value
    }

    override fun visitURI(resource: Resource, uriRef: String): Any? = uriRef
}

object LabeledVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any? = label(resource)
    override fun visitLiteral(literal: Literal): Any? = literal.string
    override fun visitURI(resource: Resource, uriRef: String): Any? = label(resource)

    private fun label(resource: Resource): String? = resource.listProperties().toList().firstOrNull {
        listOf(
            SKOS.prefLabel,
            SKOS.altLabel,
            RDFS.label
        ).contains(it.predicate)
    }?.`object`?.asLiteral()?.string

}

object ConformsToVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any =
        JsonObject().putIfNotNull("title", resource.visitWith(LabeledVisitor))

    override fun visitLiteral(literal: Literal): Any = JsonObject().put("title", literal.string)

    override fun visitURI(resource: Resource, uriRef: String): Any =
        JsonObject().put("resource", uriRef).putIfNotNull("title", resource.visitWith(LabeledVisitor))
}

object VCARDVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any =
        resource.getProperty(VCARD4.value)?.string ?: resource.getProperty(VCARD4.hasValue)?.resource?.uri ?: ""

    override fun visitLiteral(literal: Literal): Any = literal.value
    override fun visitURI(resource: Resource, uriRef: String): Any = uriRef
}

object ThemeVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any = JsonObject()

    override fun visitLiteral(literal: Literal): Any = DataTheme.getConcept(literal.string)?.let {
        JsonObject().put("id", it.identifier).putIfNotNull("title", it.label("en")).put("resource", it.resource.uri)
    } ?: JsonObject()

    override fun visitURI(resource: Resource, uriRef: String): Any = DataTheme.getConcept(resource)?.let {
        JsonObject().put("id", it.identifier).putIfNotNull("title", it.label("en")).put("resource", it.resource.uri)
    } ?: JsonObject()
}

object ProvenanceVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any =
        JsonObject().putIfNotNull("label", resource.visitWith(LabeledVisitor))

    override fun visitLiteral(literal: Literal): Any = JsonObject().put("label", literal.string)

    override fun visitURI(resource: Resource, uriRef: String): Any =
        JsonObject().put("resource", uriRef).putIfNotNull("label", resource.visitWith(LabeledVisitor))
}

object FormatVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any =
        JsonObject().apply {
            val label = resource.visitWith(LabeledVisitor) as String?
            putIfNotNull("id", label?.asNormalized())
            putIfNotNull("title", label)
        }

    override fun visitLiteral(literal: Literal): Any = FileType.getConcept(literal.string)?.let {
        JsonObject().put("id", it.identifier).put("title", it.label("en"))
    } ?: JsonObject().put("id", literal.string.asNormalized()).put("title", literal.string)

    override fun visitURI(resource: Resource, uriRef: String): Any = FileType.getConcept(resource)?.let {
        JsonObject().put("id", it.identifier).putIfNotNull("title", it.label("en"))
    } ?: FileType.getConcept(uriRef.substringAfterLast('/', uriRef))?.let {
        JsonObject().put("id", it.identifier).putIfNotNull("title", it.label("en"))
    } ?: JsonObject()
}

object LicenseVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any = JsonObject().apply {
        resource.listProperties().forEachRemaining { (_, predicate, obj) ->
            when (predicate) {
                DC_11.identifier -> putIfNotNull("id", obj.visitWith(StandardVisitor))
                SKOS.prefLabel -> putIfNotNull("description", obj.visitWith(StandardVisitor))
                SKOS.altLabel -> putIfNotNull("title", obj.visitWith(StandardVisitor))
                SKOS.exactMatch -> putIfNotNull("resource", obj.visitWith(StandardVisitor))
                EDP.licensingAssistant -> putIfNotNull("la_url", obj.visitWith(StandardVisitor))
            }
        }
    }

    override fun visitLiteral(literal: Literal): Any =
        License.exactMatch(literal.string)?.let {
            JsonObject().putIfNotNull("id", it.identifier).putIfNotNull("title", it.label("en"))
                .putIfNotNull("description", it.label("en"))
                .putIfNotNull("resource", it.exactMatch)
                .putIfNotNull("la_url", License.licenseAssistant(it))
        } ?: License.getConcept(literal.string.run {
            replace("-", "").replace('.', '_').replace("\\s+".toRegex(), "_")
        })?.let {
            JsonObject().putIfNotNull("id", it.identifier).putIfNotNull("title", it.label("en"))
                .putIfNotNull("description", it.label("en"))
                .putIfNotNull("resource", it.exactMatch)
                .putIfNotNull("la_url", License.licenseAssistant(it))
        } ?: JsonObject()
            .put("id", literal.string.asNormalized())
            .put("title", literal.string)
            .put("description", literal.string)

    override fun visitURI(resource: Resource, uriRef: String): Any = License.getConcept(resource)?.let {
        JsonObject().putIfNotNull("id", it.identifier).putIfNotNull("title", it.label("en"))
            .putIfNotNull("description", it.label("en"))
            .putIfNotNull("resource", it.exactMatch)
            .putIfNotNull("la_url", License.licenseAssistant(it))
    } ?: JsonObject()

}

object LanguageVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any? =
        resource.visitWith(LabeledVisitor)?.let {
            Languages.getConcept(it as String)?.let { concept ->
                Languages.iso6391Code(concept)
            }
        }

    override fun visitLiteral(literal: Literal): Any? =
        Languages.getConcept(literal.string)?.let { Languages.iso6391Code(it) } ?: literal.string

    override fun visitURI(resource: Resource, uriRef: String): Any? =
        Languages.getConcept(resource)?.let { Languages.iso6391Code(it) }
            ?: Languages.getConcept(uriRef.substringAfterLast('/'))?.let { Languages.iso6391Code(it) }
}

object SpatialVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any = JsonObject()
    override fun visitLiteral(literal: Literal): Any = JsonObject()
    override fun visitURI(resource: Resource, uriRef: String): Any = JsonObject().apply {
        val concept =
            Countries.getConcept(resource) ?: Continents.getConcept(resource) ?: Places.getConcept(resource)?.let {
                Places.countryOf(it)
            }
        if (concept?.resource?.uri == "http://publications.europa.eu/resource/authority/continent/EUROPE") {
            putIfNotNull("title", concept.label("en"))
            putIfNotNull("id", "eu")
        } else {
            concept?.let {
                putIfNotNull("title", it.label("en"))
                putIfNotNull("id", Countries.iso31661alpha2(it))
            }
        }
    }
}

object GeoSpatialVisitor : RDFVisitor {
    override fun visitBlank(resource: Resource, id: AnonId): Any = JsonObject().apply {
        if (resource.isA(DCTerms.Location)) {
            resource.listProperties(LOCN.geometry)
                .forEachRemaining { (_, _, obj) ->
                    if (obj.isLiteral) {
                        mergeIn(parseGeoLiteral(obj.asLiteral()), true)
                    }
                }
        }
    }

    override fun visitLiteral(literal: Literal): Any = parseGeoLiteral(literal)

    override fun visitURI(resource: Resource, uriRef: String): Any = JsonObject().apply {
        resource.listProperties(LOCN.geometry)
            .forEachRemaining { (_, _, obj) ->
                if (obj.isLiteral) {
                    mergeIn(parseGeoLiteral(obj.asLiteral()), true)
                }
            }
    }
}
