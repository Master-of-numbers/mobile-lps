package dev.mobilelps.detector

enum class SpoofingState {
    /** GNSS looks genuine. */
    CLEAN,

    /** Some indicators fired, not enough to distrust GNSS yet. */
    SUSPECT,

    /** GNSS is considered spoofed; stays latched until the signal has been clean for a while. */
    SPOOFED,

    /** No usable GNSS signal (jamming, indoors, receiver off). */
    NO_GNSS,
}

/** Spoofing indicators with their weight in the combined score. */
enum class Indicator(
    val weight: Double,
) {
    /** Satellites are received that cannot be above the horizon at the cell-tower position. */
    SATELLITES_BELOW_HORIZON(0.8),

    /** GNSS position is far outside the cell-tower position uncertainty. */
    LBS_MISMATCH(0.6),

    /** GNSS time disagrees with the network-synchronized system clock. */
    TIME_OFFSET(0.6),

    /** Position moved faster than any vehicle could. */
    POSITION_JUMP(0.6),

    /** Strong signals with almost the same C/N0 on all satellites, typical for a single spoofing transmitter. */
    CN0_UNIFORM(0.3),

    /** Signals are stronger than genuine satellites deliver at ground level. */
    CN0_HIGH(0.3),

    /** Receiver gain dropped: strong in-band signal present. */
    AGC_DROP(0.3),

    /** Pseudoranges do not fit one position: genuine and fake signals mixed. */
    HIGH_RESIDUALS(0.3),
}

data class Assessment(
    val state: SpoofingState,
    /** Combined score 0..1 (noisy-OR of indicator weights). */
    val score: Double,
    /** Fired indicators with a human-readable detail. */
    val indicators: Map<Indicator, String>,
)
