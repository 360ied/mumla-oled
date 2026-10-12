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

package se.lublin.mumla.db

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the Room database (migration slice 2).
 *
 * Single instance shared with the legacy manual path
 * ([MumlaRoomDatabase.getInstance]) — Room forbids concurrent instances on
 * one file, so both paths resolve to the same object. Provider interfaces
 * (`DatabaseProvider`) die in slice 5 with their call sites.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MumlaRoomDatabase =
        MumlaRoomDatabase.getInstance(context)

    @Provides
    fun provideMumlaDatabase(db: MumlaRoomDatabase): MumlaDatabase = db

    @Provides
    fun provideServerDao(db: MumlaRoomDatabase): ServerDao = db.serverDao()

    @Provides
    fun providePinnedDao(db: MumlaRoomDatabase): PinnedDao = db.pinnedDao()

    @Provides
    fun provideTokenDao(db: MumlaRoomDatabase): TokenDao = db.tokenDao()

    @Provides
    fun provideCommentDao(db: MumlaRoomDatabase): CommentDao = db.commentDao()

    @Provides
    fun provideLocalMuteDao(db: MumlaRoomDatabase): LocalMuteDao = db.localMuteDao()

    @Provides
    fun provideLocalIgnoreDao(db: MumlaRoomDatabase): LocalIgnoreDao = db.localIgnoreDao()

    @Provides
    fun provideCertificateDao(db: MumlaRoomDatabase): CertificateDao = db.certificateDao()

    // No ServiceModule yet — nothing new to provide (per slices.md).
}
