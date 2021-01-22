package de.westnordost.streetcomplete.data.osm.elementgeometry

import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.contentValuesOf
import de.westnordost.osmapi.map.data.BoundingBox


import javax.inject.Inject

import de.westnordost.streetcomplete.util.Serializer
import de.westnordost.osmapi.map.data.Element
import de.westnordost.osmapi.map.data.OsmLatLon
import de.westnordost.streetcomplete.data.ObjectRelationalMapping
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.ELEMENT_ID
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.ELEMENT_TYPE
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.GEOMETRY_POLYGONS
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.GEOMETRY_POLYLINES
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.CENTER_LATITUDE
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.CENTER_LONGITUDE
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.MAX_LATITUDE
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.MAX_LONGITUDE
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.MIN_LATITUDE
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.Columns.MIN_LONGITUDE
import de.westnordost.streetcomplete.data.osm.elementgeometry.ElementGeometryTable.NAME
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.osmquest.OsmQuestTable
import de.westnordost.streetcomplete.data.osm.osmquest.undo.UndoOsmQuestTable
import de.westnordost.streetcomplete.ktx.*

/** Stores the geometry of elements */
class ElementGeometryDao @Inject constructor(
    private val dbHelper: SQLiteOpenHelper,
    private val mapping: ElementGeometryEntryMapping
) {
    private val db get() = dbHelper.writableDatabase

    fun putAll(entries: Collection<ElementGeometryEntry>) {
        db.transaction {
            for (entry in entries) {
                put(entry)
            }
        }
    }

    fun put(entry: ElementGeometryEntry) {
        db.replaceOrThrow(NAME, null, mapping.toContentValues(entry))
    }

    fun get(type: Element.Type, id: Long): ElementGeometry? {
        val where = "$ELEMENT_TYPE = ? AND $ELEMENT_ID = ?"
        val args = arrayOf(type.name, id.toString())

        return db.queryOne(NAME, null, where, args) { mapping.geometry.toObject(it) }
    }

    fun getAllKeys(bbox: BoundingBox): List<ElementKey> =
        db.query(NAME,
            columns = arrayOf(ELEMENT_TYPE, ELEMENT_ID),
            selection = "$MAX_LONGITUDE >= ? AND $MAX_LATITUDE >= ? AND $MIN_LONGITUDE <= ? AND $MIN_LATITUDE <= ?",
            selectionArgs = arrayOf(
                bbox.minLongitude.toString(),
                bbox.minLatitude.toString(),
                bbox.maxLongitude.toString(),
                bbox.maxLatitude.toString()
            )
        ) {
            ElementKey(
                Element.Type.valueOf(it.getString(0)),
                it.getLong(1)
            )
        }

    fun deleteAll(entries: Iterable<ElementKey>) {
        db.transaction {
            for (entry in entries) {
                delete(entry.elementType, entry.elementId)
            }
        }
    }

    fun delete(type: Element.Type, id: Long) {
        val where = "$ELEMENT_TYPE = ? AND $ELEMENT_ID = ?"
        val args = arrayOf(type.name, id.toString())

        db.delete(NAME, where, args)
    }

    /** Cleans up element geometry entries that belong to elements that are not referenced by any
     * quest anymore.  */
    // TODO remove dis?
    fun deleteUnreferenced(): Int {
        /* SQLite does not allow selecting multiple columns in a DELETE subquery. Using a workaround
         * as described here:
         * http://blog.programmingsolution.net/sql-server-2008/tsql/delete-rows-of-a-table-matching-multiple-columns-of-another-table/
         */
        val where = "(" + ELEMENT_TYPE + LUMP + ELEMENT_ID + ") NOT IN (" +
            "SELECT " + OsmQuestTable.Columns.ELEMENT_TYPE + LUMP + OsmQuestTable.Columns.ELEMENT_ID + " FROM " + OsmQuestTable.NAME + " " +
            "UNION SELECT " + UndoOsmQuestTable.Columns.ELEMENT_TYPE + LUMP + UndoOsmQuestTable.Columns.ELEMENT_ID + " FROM " + UndoOsmQuestTable.NAME +
            ")"

        return db.delete(NAME, where, null)
    }
}

data class ElementGeometryEntry(
    val elementType: Element.Type,
    val elementId: Long,
    val geometry: ElementGeometry
)

private const val LUMP = "+'#'+"
private typealias PolyLines = ArrayList<ArrayList<OsmLatLon>>

class ElementGeometryMapping @Inject constructor(
    private val serializer: Serializer
) : ObjectRelationalMapping<ElementGeometry> {

    override fun toContentValues(obj: ElementGeometry) = contentValuesOf(
        CENTER_LATITUDE to obj.center.latitude,
        CENTER_LONGITUDE to obj.center.longitude,
        GEOMETRY_POLYGONS to (obj as? ElementPolygonsGeometry)?.let { serializer.toBytes(obj.polygons) },
        GEOMETRY_POLYLINES to (obj as? ElementPolylinesGeometry)?.let { serializer.toBytes(obj.polylines) },
        MIN_LATITUDE to obj.getBounds().minLatitude,
        MIN_LONGITUDE to obj.getBounds().minLongitude,
        MAX_LATITUDE to obj.getBounds().maxLatitude,
        MAX_LONGITUDE to obj.getBounds().maxLongitude
    )

    override fun toObject(cursor: Cursor): ElementGeometry {
        val polylines = cursor.getBlobOrNull(GEOMETRY_POLYLINES)?.let { serializer.toObject<PolyLines>(it) }
        val polygons = cursor.getBlobOrNull(GEOMETRY_POLYGONS)?.let { serializer.toObject<PolyLines>(it) }
        val center = OsmLatLon(cursor.getDouble(CENTER_LATITUDE), cursor.getDouble(CENTER_LONGITUDE))

        return when {
            polygons != null -> ElementPolygonsGeometry(polygons, center)
            polylines != null -> ElementPolylinesGeometry(polylines, center)
            else -> ElementPointGeometry(center)
        }
    }
}

class ElementGeometryEntryMapping @Inject constructor(
    val geometry: ElementGeometryMapping
): ObjectRelationalMapping<ElementGeometryEntry> {

    override fun toContentValues(obj: ElementGeometryEntry) = contentValuesOf(
        ELEMENT_TYPE to obj.elementType.name,
        ELEMENT_ID to obj.elementId
    ) + geometry.toContentValues(obj.geometry)

    override fun toObject(cursor: Cursor) = ElementGeometryEntry(
        Element.Type.valueOf(cursor.getString(ELEMENT_TYPE)),
        cursor.getLong(ELEMENT_ID),
        geometry.toObject(cursor)
    )
}
