package com.baafoo.server.broker.codec;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link KafkaProtocolVersions}.
 *
 * <p>Verifies the centralized version table and the flexible-version
 * detection logic that gates the KIP-511 ApiVersions cap.
 */
public class KafkaProtocolVersionsTest {

    @Test
    public void testSupportedApisContainsCoreApis() {
        boolean hasProduce = false, hasFetch = false, hasMetadata = false, hasApiVersions = false;
        for (int[] api : KafkaProtocolVersions.SUPPORTED_APIS) {
            switch ((short) api[0]) {
                case KafkaProtocolVersions.API_PRODUCE:      hasProduce = true; break;
                case KafkaProtocolVersions.API_FETCH:        hasFetch = true; break;
                case KafkaProtocolVersions.API_METADATA:     hasMetadata = true; break;
                case KafkaProtocolVersions.API_API_VERSIONS: hasApiVersions = true; break;
            }
        }
        assertTrue("Should advertise Produce", hasProduce);
        assertTrue("Should advertise Fetch", hasFetch);
        assertTrue("Should advertise Metadata", hasMetadata);
        assertTrue("Should advertise ApiVersions", hasApiVersions);
    }

    @Test
    public void testProduceCappedAtV8() {
        assertEquals("Produce max should be v8 (non-flexible max)",
                8, KafkaProtocolVersions.maxVersion(KafkaProtocolVersions.API_PRODUCE));
    }

    @Test
    public void testFetchCappedAtV11() {
        assertEquals("Fetch max should be v11 (non-flexible max)",
                11, KafkaProtocolVersions.maxVersion(KafkaProtocolVersions.API_FETCH));
    }

    @Test
    public void testMetadataCappedAtV8() {
        assertEquals("Metadata max should be v8 (non-flexible max)",
                8, KafkaProtocolVersions.maxVersion(KafkaProtocolVersions.API_METADATA));
    }

    @Test
    public void testApiVersionsCappedAtV2() {
        assertEquals("ApiVersions max should be v2 (KIP-511 gate)",
                2, KafkaProtocolVersions.maxVersion(KafkaProtocolVersions.API_API_VERSIONS));
    }

    @Test
    public void testIsFlexibleProduce() {
        assertFalse(KafkaProtocolVersions.isFlexible(
                KafkaProtocolVersions.API_PRODUCE, (short) 8));
        assertTrue(KafkaProtocolVersions.isFlexible(
                KafkaProtocolVersions.API_PRODUCE, (short) 9));
    }

    @Test
    public void testIsFlexibleFetch() {
        assertFalse(KafkaProtocolVersions.isFlexible(
                KafkaProtocolVersions.API_FETCH, (short) 11));
        assertTrue(KafkaProtocolVersions.isFlexible(
                KafkaProtocolVersions.API_FETCH, (short) 12));
    }

    @Test
    public void testIsFlexibleApiVersions() {
        assertFalse(KafkaProtocolVersions.isFlexible(
                KafkaProtocolVersions.API_API_VERSIONS, (short) 2));
        assertTrue(KafkaProtocolVersions.isFlexible(
                KafkaProtocolVersions.API_API_VERSIONS, (short) 3));
    }

    @Test
    public void testIsFlexibleUnknownApi() {
        assertFalse(KafkaProtocolVersions.isFlexible((short) 99, (short) 100));
    }

    @Test
    public void testMaxVersionUnknownApi() {
        assertEquals(0, KafkaProtocolVersions.maxVersion((short) 99));
    }
}
