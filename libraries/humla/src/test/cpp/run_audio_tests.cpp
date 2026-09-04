/*
 * Copyright (C) 2026 Mumla Developers
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

#include <iostream>

void run_biquad_filter_tests();
void run_soft_limiter_tests();
void run_pre_speech_ring_buffer_tests();
void run_adaptive_leveler_tests();
void run_hysteresis_vad_tests();

int main() {
    std::cout << "========================================" << std::endl;
    std::cout << " Mumla Native Audio Engine Tests" << std::endl;
    std::cout << "========================================" << std::endl;

    run_biquad_filter_tests();
    std::cout << std::endl;

    run_soft_limiter_tests();
    std::cout << std::endl;

    run_pre_speech_ring_buffer_tests();
    std::cout << std::endl;

    run_adaptive_leveler_tests();
    std::cout << std::endl;

    run_hysteresis_vad_tests();
    std::cout << std::endl;

    std::cout << "========================================" << std::endl;
    std::cout << " ALL NATIVE AUDIO ENGINE TESTS PASSED!" << std::endl;
    std::cout << "========================================" << std::endl;
    return 0;
}
