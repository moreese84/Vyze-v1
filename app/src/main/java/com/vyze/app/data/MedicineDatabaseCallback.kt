package com.vyze.app.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Seeds the medicine database with common Malaysian medicines. Runs as a
 * Room database callback.
 *
 * Medicines are sourced from:
 * - Malaysian Ministry of Health OTC drug list
 * - Common pharmacy stock in Malaysia (Guardian, Watsons, farmasi)
 * - Frequently encountered medicine labels in Malaysian households
 *
 * Whole-app audit P2 #16/#17 (2026-11-09): the seed ran once in onCreate,
 * fire-and-forget, which left upgraded databases (onCreate never
 * re-fires) with an empty medicines table forever (#16) and raced
 * first-launch readers against the background insert (#17; the reader
 * path also built a SECOND Room database instance). The seed now runs
 * synchronously inside onOpen — Room holds the open lock there, so every
 * path (fresh install, version upgrade, destructive fallback) is seeded
 * before the first query returns — as idempotent INSERT..WHERE NOT
 * EXISTS statements, so re-runs on later opens change nothing.
 */
object MedicineDatabaseCallback {

    fun createCallback() = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            try {
                for (statement in companionMedicineSeedStatements(getCommonMedicines())) {
                    db.execSQL(statement)
                }
            } catch (e: Exception) {
                Log.e("MedicineDB", "Reference seed failed: ${e.message}", e)
            }
        }
    }

    /**
     * Idempotent seed statements for [medicines]: each inserts only when
     * no row with the same searchName exists yet, so re-running after an
     * upgrade or a later open never duplicates rows.
     */
    fun companionMedicineSeedStatements(medicines: List<MedicineEntity>): List<String> =
        medicines.map { medicine ->
            "INSERT INTO medicines " +
                "(name, genericName, dosage, frequency, warnings, category, searchName) " +
                "SELECT ${sqlLiteral(medicine.name)}, " +
                "${sqlLiteral(medicine.genericName)}, " +
                "${sqlLiteral(medicine.dosage)}, " +
                "${sqlLiteral(medicine.frequency)}, " +
                "${sqlLiteral(medicine.warnings)}, " +
                "${sqlLiteral(medicine.category)}, " +
                "${sqlLiteral(medicine.searchName)} " +
                "WHERE NOT EXISTS (SELECT 1 FROM medicines " +
                "WHERE searchName = ${sqlLiteral(medicine.searchName)})"
        }

    private fun sqlLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun commonMedicinesForTests(): List<MedicineEntity> = getCommonMedicines()

    /**
     * Common Malaysian medicines — OTC and widely prescribed.
     * Search names are lowercase with no special characters for matching.
     */
    private fun getCommonMedicines(): List<MedicineEntity> = listOf(
        // ── Pain Relief / Anti-inflammatory ──────────────────────
        med("Panadol", "Paracetamol", "500mg tablet", "One to two tablets every 4 to 6 hours as needed. Maximum 8 tablets per day.", "Do not exceed recommended dose. Avoid with liver disease.", "Pain relief"),
        med("Panadol Extra", "Paracetamol + Caffeine", "500mg/65mg tablet", "One to two tablets every 4 to 6 hours as needed.", "Contains caffeine. May cause sleeplessness if taken late.", "Pain relief"),
        med("Panadol Soluble", "Paracetamol", "500mg soluble tablet", "Dissolve in water. One to two tablets every 4 to 6 hours.", "For those who have difficulty swallowing tablets.", "Pain relief"),
        med("Ibuprofen", "Ibuprofen", "200mg tablet", "One to two tablets every 4 to 6 hours after meals.", "Take with food. Avoid if allergic to NSAIDs. Not for stomach ulcers.", "Pain relief"),
        med("Brufen", "Ibuprofen", "400mg tablet", "One tablet three times daily after meals.", "Take with food. Avoid in third trimester of pregnancy.", "Pain relief"),
        med("Diclac Retard", "Diclofenac sodium", "100mg modified-release tablet", "One tablet daily after meals.", "Take after meals. Avoid if allergic to NSAIDs. May cause stomach bleeding.", "Pain relief"),
        med("Voltaren", "Diclofenac sodium", "25mg tablet", "One to two tablets two to three times daily after meals.", "Take with food. Monitor for stomach pain.", "Pain relief"),
        med("Nurofen", "Ibuprofen", "200mg tablet", "One to two tablets every 4 to 6 hours after meals.", "Take with food. Not suitable for children under 12.", "Pain relief"),
        med("Paracetamol", "Paracetamol", "500mg tablet", "One to two tablets every 4 to 6 hours as needed.", "Maximum 4g per day. Avoid with alcohol.", "Pain relief"),
        med("Advil", "Ibuprofen", "200mg tablet", "One tablet every 4 to 6 hours after meals.", "Take with food. Avoid if you have kidney problems.", "Pain relief"),

        // ── Cold & Flu ──────────────────────────────────────────
        med("Actifed", "Pseudoephedrine + Triprolidine", "60mg/2.5mg tablet", "One tablet every 4 to 6 hours.", "May cause drowsiness. Do not drive. Avoid with MAO inhibitors.", "Cold and flu"),
        med("Decolgen", "Phenylpropanolamine + Chlorpheniramine", "25mg/2mg tablet", "One tablet every 4 to 6 hours.", "May cause drowsiness. Avoid with high blood pressure medication.", "Cold and flu"),
        med("Zirtec", "Cetirizine", "10mg tablet", "One tablet once daily.", "May cause mild drowsiness. Safe for adults and children over 6.", "Allergy"),
        med("Clarityn", "Loratadine", "10mg tablet", "One tablet once daily.", "Non-drowsy antihistamine. Safe for daily use.", "Allergy"),
        med("Telfast", "Fexofenadine", "120mg tablet", "One tablet once daily.", "Non-drowsy. Do not take with fruit juice.", "Allergy"),
        med("Benadryl", "Diphenhydramine", "25mg capsule", "One capsule every 4 to 6 hours.", "Causes drowsiness. Do not drive. Not for children under 6.", "Allergy"),

        // ── Antibiotics (Prescription) ───────────────────────────
        med("Amoxicillin", "Amoxicillin", "500mg capsule", "One capsule three times daily for 5 to 7 days.", "Complete the full course. Take with or without food.", "Antibiotic"),
        med("Augmentin", "Amoxicillin + Clavulanic acid", "625mg tablet", "One tablet three times daily for 5 to 7 days.", "Take with food to reduce stomach upset. Complete full course.", "Antibiotic"),
        med("Azithromycin", "Azithromycin", "500mg tablet", "One tablet daily for 3 days.", "Take on empty stomach or with food. Complete the course.", "Antibiotic"),
        med("Ciprofloxacin", "Ciprofloxacin", "500mg tablet", "One tablet twice daily for 5 to 7 days.", "Take with plenty of water. Avoid dairy products within 2 hours.", "Antibiotic"),

        // ── Gastric / Digestive ──────────────────────────────────
        med("Omeprazole", "Omeprazole", "20mg capsule", "One capsule once daily before breakfast.", "Take 30 minutes before food. Do not crush capsules.", "Gastric"),
        med("Losec", "Omeprazole", "20mg capsule", "One capsule once daily before breakfast.", "For acid reflux and stomach ulcers. Take before meals.", "Gastric"),
        med("Pantoprazole", "Pantoprazole", "40mg tablet", "One tablet once daily before breakfast.", "For GERD and gastric ulcers. Take 30 minutes before food.", "Gastric"),
        med("Gaviscon", "Sodium alginate + Sodium bicarbonate", "10ml liquid", "10ml after meals and at bedtime.", "For heartburn and acid reflux. Do not lie down after taking.", "Gastric"),
        med("Imodium", "Loperamide", "2mg capsule", "Two capsules initially, then one after each loose stool.", "Maximum 8 capsules per day. See doctor if symptoms persist 48 hours.", "Digestive"),
        med("Buscopan", "Hyoscine butylbromide", "10mg tablet", "One to two tablets three times daily.", "For stomach cramps. May cause dry mouth. Avoid in glaucoma.", "Digestive"),

        // ── Vitamins & Supplements ───────────────────────────────
        med("Neurobion", "Vitamin B complex (B1+B6+B12)", "Tablet", "One tablet once daily.", "For nerve health. Safe for long-term use.", "Vitamin"),
        med("Surfshot", "Vitamin C", "500mg tablet", "One tablet once daily.", "For immune support. Take with water.", "Vitamin"),
        med("Caltrate", "Calcium + Vitamin D3", "600mg/400IU tablet", "One tablet once daily with food.", "For bone health. Take with food for better absorption.", "Vitamin"),
        med("Blackmores Fish Oil", "Omega-3 fish oil", "1000mg capsule", "One to three capsules daily with food.", "For heart and joint health. Take with food.", "Supplement"),
        med("Centrum", "Multivitamin", "Tablet", "One tablet once daily with food.", "Complete daily vitamin. Take with food.", "Vitamin"),

        // ── Skin / Topical ───────────────────────────────────────
        med("Betnovate", "Betamethasone valerate", "0.1% cream", "Apply thin layer to affected area 1 to 2 times daily.", "For skin inflammation. Do not use on face for more than 5 days. Avoid broken skin.", "Skin"),
        med("Fucicort", "Fusidic acid + Betamethasone", "2% cream", "Apply thin layer to affected area 2 to 3 times daily.", "For infected skin conditions. Do not use for more than 2 weeks.", "Skin"),
        med("Savlon", "Chlorhexidine + Cetrimide", "Antiseptic liquid", "Dilute with water. Clean wound and apply.", "For minor cuts and wounds. Do not use in eyes or ears.", "Skin"),

        // ── Diabetes ─────────────────────────────────────────────
        med("Metformin", "Metformin hydrochloride", "500mg tablet", "One tablet twice daily with meals.", "Take with food. May cause initial stomach upset. Monitor blood sugar.", "Diabetes"),
        med("Glucophage", "Metformin hydrochloride", "500mg tablet", "One to two tablets twice daily with meals.", "For type 2 diabetes. Take with food to reduce side effects.", "Diabetes"),

        // ── Blood Pressure ───────────────────────────────────────
        med("Amlodipine", "Amlodipine besylate", "5mg tablet", "One tablet once daily.", "For high blood pressure. Do not stop suddenly. Avoid grapefruit.", "Blood pressure"),
        med("Losartan", "Losartan potassium", "50mg tablet", "One tablet once daily.", "For hypertension. Avoid during pregnancy. Monitor potassium levels.", "Blood pressure"),

        // ── Malaysian Traditional / Common ───────────────────────
        med("Tolak Angin", "Herbal blend", "15ml liquid", "One sachet after meals.", "For bloating and mild stomach discomfort. Traditional herbal remedy.", "Traditional"),
        med("Minyak Angin Cap Kapak", "Menthol + Eucalyptus oil", "Topical oil", "Apply to forehead and temples as needed.", "For headache and nasal congestion. Do not apply near eyes.", "Traditional"),
        med("Ubat Gigi", "Herbal toothpaste", "Toothpaste", "Brush teeth twice daily.", "Traditional herbal toothpaste for gum health.", "Traditional"),
    )

    private fun med(
        name: String,
        genericName: String,
        dosage: String,
        frequency: String,
        warnings: String,
        category: String
    ) = MedicineEntity(
        name = name,
        genericName = genericName,
        dosage = dosage,
        frequency = frequency,
        warnings = warnings,
        category = category,
        searchName = name.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
    )
}
