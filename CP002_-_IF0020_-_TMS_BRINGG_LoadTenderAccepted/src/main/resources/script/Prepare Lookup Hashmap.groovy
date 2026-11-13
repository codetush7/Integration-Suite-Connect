import com.sap.gateway.ip.core.customdev.util.Message
import groovy.util.XmlSlurper
import groovy.json.JsonOutput
import java.io.Reader

def Message processData(Message message) {

    // Read payload as Reader
    Reader body = message.getBody(Reader)

    // Parse XML
    def parsedXml = new XmlSlurper().parse(body)

    // Convert XML rows into maps
    def rows = parsedXml.select_response.row.collect { row ->

        row.children().collectEntries { field ->
            [(field.name().toString()): field.text()]
        }
    }

    // Group rows using composite key (TMS_HUB + CARRIER)
    def groupedMap = rows.groupBy { row ->
        [row.TMS_HUB, row.CARRIER].join("||")
    }

    // Store as property
    message.setProperty(
        "METRO_LOCATION_XREF_LookupKey_TMS_HUB_CARRIER",
        JsonOutput.toJson(groupedMap)
    )

    // Set response body (for debugging purpose)
    message.setBody(JsonOutput.toJson(groupedMap))

    return message
}