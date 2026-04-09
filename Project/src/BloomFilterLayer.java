import java.util.BitSet;

/**
 * A simple Bloom Filter for memory-efficient URL deduplication.
 * Uses multiple hash functions to map elements to positions in a bit array.
 *
 * No external dependencies. Uses a custom double-hashing scheme
 * with Java's built-in hashCode and a secondary FNV-style hash.
 */
public class BloomFilterLayer {

    private final BitSet bitArray;
    private final int bitArraySize;
    private final int hashFunctionCount;
    private int insertionCount;

    /**
     * Creates a Bloom Filter optimized for the given parameters.
     *
     * @param expectedInsertions estimated number of unique elements
     * @param falsePositiveRate  desired false positive probability (e.g. 0.01 for 1%)
     */
    public BloomFilterLayer(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            expectedInsertions = 1000;
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            falsePositiveRate = 0.01;
        }

        // Optimal bit array size: m = -(n * ln(p)) / (ln(2))^2
        this.bitArraySize = (int) Math.ceil(
            -(expectedInsertions * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2))
        );

        // Optimal number of hash functions: k = (m/n) * ln(2)
        this.hashFunctionCount = Math.max(1, (int) Math.round(
            ((double) bitArraySize / expectedInsertions) * Math.log(2)
        ));

        this.bitArray = new BitSet(bitArraySize);
        this.insertionCount = 0;
    }

    /**
     * Overloaded constructor with default 1% false positive rate.
     */
    public BloomFilterLayer(int expectedInsertions) {
        this(expectedInsertions, 0.01);
    }

    /**
     * Checks whether an element MIGHT be in the set.
     *   false = definitely not present (safe to add)
     *   true  = probably present (check HashSet to confirm)
     */
    public boolean mightContain(String element) {
        for (int i = 0; i < hashFunctionCount; i++) {
            int position = getHash(element, i);
            if (!bitArray.get(position)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Inserts an element into the filter by setting k bit positions.
     * Returns true if the bits changed (element likely new).
     */
    public boolean insert(String element) {
        boolean anyBitChanged = false;
        for (int i = 0; i < hashFunctionCount; i++) {
            int position = getHash(element, i);
            if (!bitArray.get(position)) {
                anyBitChanged = true;
                bitArray.set(position);
            }
        }
        if (anyBitChanged) {
            insertionCount++;
        }
        return anyBitChanged;
    }

    /**
     * Returns the number of elements inserted.
     */
    public int getInsertionCount() {
        return insertionCount;
    }

    /**
     * Returns the bit array size in bits.
     */
    public int getBitArraySize() {
        return bitArraySize;
    }

    /**
     * Returns the number of hash functions used.
     */
    public int getHashFunctionCount() {
        return hashFunctionCount;
    }

    /**
     * Estimates the current false positive rate based on
     * the number of set bits and the filter parameters.
     *
     * FPR = (1 - e^(-k*n/m))^k
     */
    public double estimatedFalsePositiveRate() {
        double exponent = -((double) hashFunctionCount * insertionCount) / bitArraySize;
        return Math.pow(1.0 - Math.exp(exponent), hashFunctionCount);
    }

    /**
     * Generates the i-th hash position using double hashing.
     * h_i(x) = (h1(x) + i * h2(x)) mod m
     *
     * h1 is Java's hashCode, h2 is FNV-1a inspired.
     * This avoids needing k independent hash functions.
     */
    private int getHash(String element, int i) {
        int h1 = element.hashCode();
        int h2 = fnvHash(element);
        int combined = h1 + (i * h2);
        return Math.floorMod(combined, bitArraySize);
    }

    /**
     * FNV-1a inspired hash for the secondary hash function.
     */
    private static int fnvHash(String value) {
        int hash = 0x811c9dc5;
        for (int j = 0; j < value.length(); j++) {
            hash ^= value.charAt(j);
            hash *= 0x01000193;
        }
        return hash;
    }
}
