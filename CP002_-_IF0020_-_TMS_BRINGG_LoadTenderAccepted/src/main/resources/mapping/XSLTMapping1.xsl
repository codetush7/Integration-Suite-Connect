<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:ns0="http://sap.com/xi/XI/SplitAndMerge" exclude-result-prefixes="xsl ns0">
<xsl:output
    method="xml" indent="yes"/>
<!-- Identity template (copy as is) -->
<xsl:template match="@*|node()">
<xsl:copy>
<xsl:apply-templates select="@*|node()"/>
</xsl:copy>
</xsl:template>
<!-- Root handling -->
<xsl:template match="/">
<xsl:choose>
<!-- If already wrapped, pass as is -->
<xsl:when test="/*[local-name()='Messages' and namespace-uri()='http://sap.com/xi/XI/SplitAndMerge']">
<xsl:copy-of select="*"/>
</xsl:when>
<!-- Otherwise wrap -->
<xsl:otherwise>
<ns0:Messages>
<ns0:Message1>
<xsl:copy-of select="*"/>
</ns0:Message1>
</ns0:Messages>
</xsl:otherwise>
</xsl:choose>
</xsl:template>
</xsl:stylesheet>