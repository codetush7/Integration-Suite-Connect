<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:output method="xml" indent="yes"/>

    <!-- Identity template to copy nodes and attributes -->
    <xsl:template match="@* | node()">
        <xsl:copy>
            <xsl:apply-templates select="@* | node()"/>
        </xsl:copy>
    </xsl:template>

    <!-- Template for CISDocument -->
    <xsl:template match="CISDocument">
        <xsl:copy>
            <!-- Copy other elements as is -->
            <xsl:apply-templates select="*[not(self::ShipmentLeg)]"/>

            <!-- Sort and copy ShipmentLeg elements -->
            <xsl:apply-templates select="ShipmentLeg">
                <xsl:sort select="DropStopSequenceNumber" data-type="number" order="ascending"/>
            </xsl:apply-templates>
        </xsl:copy>
    </xsl:template>
</xsl:stylesheet>