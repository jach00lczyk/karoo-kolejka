package pl.kolejka.karoo.domain.timetable

import pl.kolejka.karoo.domain.model.Connection
import pl.kolejka.karoo.domain.model.Station
import java.time.LocalDate

/**
 * Dostęp do rozkładu. Implementacja produkcyjna: SQLite read-only (moduł app, faza 2);
 * w testach: [InMemoryTimetable].
 */
interface Timetable {
    val version: String
    val stations: List<Station>

    /**
     * Kursy odjeżdżające z którejś stacji [from] w dniu kursowania [serviceDate] z `dep_s` w [depRange]
     * (z możliwością wsiadania), które później zatrzymują się na którejś stacji [targets]
     * (z możliwością wysiadania). Jeden wiersz na każdą parę (kurs, stacja celu).
     */
    fun connections(from: Set<Int>, targets: Set<Int>, serviceDate: LocalDate, depRange: IntRange): List<Connection>
}
