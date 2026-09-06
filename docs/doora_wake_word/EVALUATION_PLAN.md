# DooraGo Wake-Word Quality Evaluation & Acceptance Criteria

This document establishes the empirical evaluation benchmarks and quality gates that any candidate `doora_kws.tflite` model must pass prior to production integration.

---

## 1. Key Evaluation Metrics

1. **False Reject Rate (FRR) / Missed Detection Rate**:
   - Percentage of clean or mildly noisy spoken "Doora" utterances that the model fails to detect.
   - **Target**: $\text{FRR} \le 5.0\%$ across unseen holdout speakers.
2. **False Accept Rate (FAR) / False Alarm Rate**:
   - Number of false activations during non-target speech or environmental audio.
   - **Target**: $< 1\text{ false alarm}$ per 10 hours of continuous non-target speech / TV / ambient background audio.
3. **Inference Latency**:
   - Time required to process a 100ms audio chunk on mobile ARM64 hardware.
   - **Target**: $< 12\text{ms}$ on low/mid-range Android devices.
4. **Binary Size & RAM Footprint**:
   - **Target Size**: $\le 1.0\text{ MB}$ (Int8 quantized).
   - **Target RAM**: $\le 4.0\text{ MB}$ peak allocation.

---

## 2. Threshold Calibration Matrix (ROC / DET Curve)

The confidence threshold (e.g. $0.70 - 0.90$) must not be chosen arbitrarily. Plot a Detection Error Tradeoff (DET) curve evaluating FAR vs. FRR across the speaker-disjoint validation set:

```text
FAR (False Alarms / 10hr)
▲
│   ● Threshold = 0.65 (FRR: 2%, FAR: 6.2/10hr) -> Too many false wakeups
│     ● Threshold = 0.75 (FRR: 3.5%, FAR: 1.8/10hr)
│       ★ Threshold = 0.85 (FRR: 4.8%, FAR: 0.4/10hr) [RECOMMENDED SWEET SPOT]
│         ● Threshold = 0.95 (FRR: 18.2%, FAR: 0.05/10hr) -> Too strict / Misses quiet speech
└────────────────────────────────────────────────────────► FRR (%)
```

---

## 3. Production Quality Acceptance Gates

| Gate # | Quality Gate Description | Status Condition |
| :--- | :--- | :--- |
| **Gate 1** | Model evaluated on speaker-disjoint test set ($\ge 5$ unseen speakers) with $\text{FRR} \le 5\%$. | **Mandatory** |
| **Gate 2** | Model evaluated on 12 hours of continuous background conversational audio with $\le 1$ false trigger. | **Mandatory** |
| **Gate 3** | Int8 Quantization verified with zero layer fallback to float64/float32. | **Mandatory** |
| **Gate 4** | Inference execution time confirmed $< 15\text{ms}$ on Android device. | **Mandatory** |
| **Gate 5** | Memory leak verification: 24-hour continuous loop with zero allocation growth. | **Mandatory** |
