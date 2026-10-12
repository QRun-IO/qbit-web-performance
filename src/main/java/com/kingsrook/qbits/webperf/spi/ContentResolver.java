/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.webperf.spi;


import java.io.Serializable;


/*******************************************************************************
 ** Maps a public URL path to the record it renders (design §3.3), so tested
 ** pages link to their CMS records and record screens can show their
 ** Performance panel. A CMS host adapts its existing path resolver (kof22: the
 ** analytics ContentPathResolver), so one mapping serves both.
 *******************************************************************************/
public interface ContentResolver extends Serializable
{
   /*******************************************************************************
    ** A record a path renders.
    **
    ** @param tableName the QQQ table name
    ** @param id        the record id
    *******************************************************************************/
   record ContentRef(String tableName, Long id) implements Serializable
   {
   }



   /*******************************************************************************
    ** The record a path renders, or null when it renders none.
    **
    ** @param path the URL path (no query string)
    ** @return the record or null
    *******************************************************************************/
   ContentRef resolve(String path);
}
