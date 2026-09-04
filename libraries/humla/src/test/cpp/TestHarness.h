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

#ifndef MUMLA_TEST_HARNESS_H_
#define MUMLA_TEST_HARNESS_H_

#include <cmath>
#include <iostream>
#include <type_traits>

inline int g_testFailures = 0;
inline int g_testCount = 0;

namespace mumla::test {

template <typename T, typename U>
inline bool compareEqual(const T& a, const U& b) {
    if constexpr (std::is_integral_v<T> && std::is_integral_v<U>) {
        if constexpr (std::is_signed_v<T> == std::is_signed_v<U>) {
            return a == b;
        } else if constexpr (std::is_signed_v<T>) {
            return a >= 0 && static_cast<std::make_unsigned_t<T>>(a) == b;
        } else {
            return b >= 0 && a == static_cast<std::make_unsigned_t<U>>(b);
        }
    } else {
        return a == b;
    }
}

} // namespace mumla::test

#define TEST_ASSERT(cond) do { \
    if (!(cond)) { \
        std::cerr << "  [FAIL] " << __FILE__ << ":" << __LINE__ << " (" << #cond << ")" << std::endl; \
        g_testFailures++; \
    } \
} while (0)

#define TEST_ASSERT_TRUE(cond) TEST_ASSERT(cond)
#define TEST_ASSERT_FALSE(cond) TEST_ASSERT(!(cond))

#define TEST_ASSERT_EQ(a, b) do { \
    auto _valA = (a); \
    auto _valB = (b); \
    if (!mumla::test::compareEqual(_valA, _valB)) { \
        std::cerr << "  [FAIL] " << __FILE__ << ":" << __LINE__ << " (" #a " == " #b ") [" \
                  << _valA << " != " << _valB << "]" << std::endl; \
        g_testFailures++; \
    } \
} while (0)

#define TEST_ASSERT_NEAR(a, b, eps) do { \
    auto _valA = (a); \
    auto _valB = (b); \
    if (std::abs(_valA - _valB) > (eps)) { \
        std::cerr << "  [FAIL] " << __FILE__ << ":" << __LINE__ << " (|(" #a ") - (" #b ")| <= " #eps ") [" \
                  << "diff " << std::abs(_valA - _valB) << " > " << (eps) << "]" << std::endl; \
        g_testFailures++; \
    } \
} while (0)

#endif // MUMLA_TEST_HARNESS_H_
