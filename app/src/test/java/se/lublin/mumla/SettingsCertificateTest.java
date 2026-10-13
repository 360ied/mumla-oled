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

package se.lublin.mumla;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;

import junit.framework.TestCase;

public class SettingsCertificateTest extends TestCase {

    public void testCertificateDefaultState() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        assertEquals(-1L, settings.getDefaultCertificate());
        assertFalse("By default, certificate should not be used", settings.isUsingCertificate());
    }

    public void testSetDefaultCertificateId() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        long testCertId = 42L;
        settings.setDefaultCertificateId(testCertId);

        assertEquals(testCertId, settings.getDefaultCertificate());
        assertTrue("Certificate should be active after setDefaultCertificateId", settings.isUsingCertificate());
        assertEquals(testCertId, (long) TestDataStores.get(store, TestDataStores.longKey(Settings.PREF_CERT_ID), -1L));
    }

    public void testDisableCertificate() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setDefaultCertificateId(123L);
        assertTrue(settings.isUsingCertificate());

        settings.disableCertificate();
        assertEquals(-1L, settings.getDefaultCertificate());
        assertFalse(settings.isUsingCertificate());
        assertEquals(-1L, (long) TestDataStores.get(store, TestDataStores.longKey(Settings.PREF_CERT_ID), 0L));
    }

    public void testSwitchCertificateId() {
        DataStore<Preferences> store = TestDataStores.create();
        Settings settings = Settings.createForTesting(store);

        settings.setDefaultCertificateId(1L);
        assertEquals(1L, settings.getDefaultCertificate());

        settings.setDefaultCertificateId(2L);
        assertEquals(2L, settings.getDefaultCertificate());
        assertTrue(settings.isUsingCertificate());
    }
}
