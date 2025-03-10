package com.splicemachine.derby.utils;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.derby.impl.sql.catalog.Splice_DD_Version;
import com.splicemachine.derby.impl.sql.catalog.upgrade.SpliceCatalogUpgradeScripts;
import com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScript;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class SpliceCatalogUpgradeScriptsTest {
    String s1 =
            "VERSION2.1938: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptForTriggerWhenClause\n" +
            "VERSION2.1940: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptForReplicationSystemTables\n" +
            "VERSION2.1953: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptForRemoveUnusedIndexInSYSFILESTable\n" +
            "VERSION2.1959: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptForTriggerMultipleStatements\n" +
            "VERSION2.1971: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptToAddCatalogVersion\n" +
            "VERSION2.1974: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptToAddMinRetentionPeriodColumnToSYSTABLES\n" +
            "VERSION3.1979: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptToSetJavaClassNameColumnInSYSALIASES\n" +
            "VERSION4.1985: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptToAddSysNaturalNumbersTable\n";

    String s2 = "VERSION4.1989: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptToAddIndexColUseViewInSYSCAT\n" +
            "VERSION4.1992: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptForTablePriorities\n" +
            "VERSION4.2003: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeStoredObjects\n" +
            "VERSION4.2023: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeFixIndexDescriptors\n";

    // Those scripts must run before other upgrade scripts
    String s3 = "VERSION4.2020: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeAddConglomerateNumberIndex\n" +
            "VERSION4.2024: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptToPrioritizeSchemaIdInSystemIndices\n" +
            "VERSION4.1996: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeConglomerateTable\n" +
            "VERSION4.2020: com.splicemachine.derby.impl.sql.catalog.upgrade.UpgradeScriptToAddMultiDatabaseSupport\n";
    // add more scripts here

    private String replaceVersions(String s) {
        return s.replace("VERSION2", SpliceCatalogUpgradeScripts.baseVersion2.toString())
                .replace("VERSION3", SpliceCatalogUpgradeScripts.baseVersion3.toString())
                .replace("VERSION4", SpliceCatalogUpgradeScripts.baseVersion4.toString());
        // add more base versions here.
    }

    @Test
    public void test_since_1933()
    {
        SpliceCatalogUpgradeScripts s = new SpliceCatalogUpgradeScripts(null, null, new Properties());
        Splice_DD_Version version = new Splice_DD_Version(null, 3,1,0, 1933);
        List<SpliceCatalogUpgradeScripts.VersionAndUpgrade> list =
                SpliceCatalogUpgradeScripts.getScriptsToUpgrade(s.getScripts(), version);
        Assert.assertEquals(replaceVersions(s3 + s1 + s2), getUpgradeScriptsToStr(list));
    }

    @Test
    public void test_since_1987()
    {
        SpliceCatalogUpgradeScripts s = new SpliceCatalogUpgradeScripts(null, null, new Properties());
        Splice_DD_Version version = new Splice_DD_Version(null, 3,2,0, 1987);
        List<SpliceCatalogUpgradeScripts.VersionAndUpgrade> list =
                SpliceCatalogUpgradeScripts.getScriptsToUpgrade(s.getScripts(), version);
        Assert.assertEquals(replaceVersions(s3 + s2), getUpgradeScriptsToStr(list));
    }

    @Test
    public void test_since_2000_upgrade_empty()
    {
        SpliceCatalogUpgradeScripts s = new SpliceCatalogUpgradeScripts(null, null, new Properties());
        Splice_DD_Version version = new Splice_DD_Version(null, 4,0,0, 2000);
        List<SpliceCatalogUpgradeScripts.VersionAndUpgrade> list =
                SpliceCatalogUpgradeScripts.getScriptsToUpgrade(s.getScripts(), version);
        Assert.assertEquals("", getUpgradeScriptsToStr(list));
    }

    @Test
    public void test_upgrade_run() throws StandardException {
        List<SpliceCatalogUpgradeScripts.VersionAndUpgrade> list = new ArrayList<>();
        final int[] counter = {0};
        UpgradeScript s = new UpgradeScript() {
            @Override
            public void run() throws StandardException {
                // nothing
                counter[0]++;
            }
        };
        // add unsorted and multiple for one version
        list.add(new SpliceCatalogUpgradeScripts.VersionAndUpgrade(new Splice_DD_Version(null, 3,1,0, 1900), s));
        list.add(new SpliceCatalogUpgradeScripts.VersionAndUpgrade(new Splice_DD_Version(null, 3,1,0, 1900), s));
        list.add(new SpliceCatalogUpgradeScripts.VersionAndUpgrade(new Splice_DD_Version(null, 2,8,0, 1930), s));
        list.add(new SpliceCatalogUpgradeScripts.VersionAndUpgrade(new Splice_DD_Version(null, 3,1,0, 1940), s));

        Assert.assertEquals( 4, SpliceCatalogUpgradeScripts.getScriptsToUpgrade(list,
                new Splice_DD_Version(null, 2,7,0, 1999)).size() );
        Assert.assertEquals( 4, SpliceCatalogUpgradeScripts.getScriptsToUpgrade(list,
                new Splice_DD_Version(null, 2,8,0, 1900)).size() );
        Assert.assertEquals( 3, SpliceCatalogUpgradeScripts.getScriptsToUpgrade(list,
                new Splice_DD_Version(null, 3,0,0, 1999)).size() );
        Assert.assertEquals( 3, SpliceCatalogUpgradeScripts.getScriptsToUpgrade(list,
                new Splice_DD_Version(null, 3,1,0, 1899)).size() );
        Assert.assertEquals( 1, SpliceCatalogUpgradeScripts.getScriptsToUpgrade(list,
                new Splice_DD_Version(null, 3,1,0, 1900)).size() );
        Assert.assertEquals( 1, SpliceCatalogUpgradeScripts.getScriptsToUpgrade(list,
                new Splice_DD_Version(null, 3,1,0, 1939)).size() );
        Assert.assertEquals( 0, SpliceCatalogUpgradeScripts.getScriptsToUpgrade(list,
                new Splice_DD_Version(null, 3,1,0, 1940)).size() );
        Assert.assertEquals( 0, SpliceCatalogUpgradeScripts.getScriptsToUpgrade(list,
                new Splice_DD_Version(null, 4,0,0, 0)).size() );

        SpliceCatalogUpgradeScripts.runAllScripts(list, null, null);
        Assert.assertEquals(4, counter[0]);
    }

    String getUpgradeScriptsToStr(List<SpliceCatalogUpgradeScripts.VersionAndUpgrade> upgradeNeeded)
    {
        StringBuilder sb = new StringBuilder(100);
        for( SpliceCatalogUpgradeScripts.VersionAndUpgrade el : upgradeNeeded ) {
            sb.append(el.version + ": " + el.script.getClass().getName() + "\n");
        }
        return sb.toString();
    }
}
