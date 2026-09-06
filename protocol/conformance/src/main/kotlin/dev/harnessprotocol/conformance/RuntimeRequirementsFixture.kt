package dev.harnessprotocol.conformance

/**
 * Requirement admission against a real runtime with controlled model responses.
 * Closing the fixture releases its model boundary and any harnesses it created.
 * This seam deliberately does not promise arbitrary runtime fault injection.
 */
interface RuntimeRequirementsFixture : ProfileFixture, AutoCloseable {
    val observation: RuntimeObservation
}
