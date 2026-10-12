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

package com.kingsrook.qbits.webperf.processes;


import java.util.ArrayList;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;


/*******************************************************************************
 ** Summary lines for the Review (future tense) and Result (past tense)
 ** screens; the frontend picks singular or plural by count.
 *******************************************************************************/
final class Lines
{
   /*******************************************************************************
    ** Helpers only.
    *******************************************************************************/
   private Lines()
   {
   }



   /*******************************************************************************
    ** A counted line.
    **
    ** @param status         OK, INFO, WARNING or ERROR
    ** @param singularFuture e.g. "page will be tested"
    ** @param pluralFuture   e.g. "pages will be tested"
    ** @param singularPast   e.g. "page was queued"
    ** @param pluralPast     e.g. "pages were queued"
    ** @return the line
    *******************************************************************************/
   static ProcessSummaryLine line(Status status, String singularFuture, String pluralFuture, String singularPast, String pluralPast)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage(singularFuture);
      line.setPluralFutureMessage(pluralFuture);
      line.setSingularPastMessage(singularPast);
      line.setPluralPastMessage(pluralPast);
      return (line);
   }



   /*******************************************************************************
    ** A single message line (no count), shown on both screens.
    **
    ** @param status  the status
    ** @param message the message
    ** @return the line
    *******************************************************************************/
   static ProcessSummaryLine message(Status status, String message)
   {
      return (new ProcessSummaryLine(status, null, message));
   }



   /*******************************************************************************
    ** Add lines that have a count (or no count at all: message lines).
    **
    ** @param target the list
    ** @param lines  the lines
    *******************************************************************************/
   static void addAll(ArrayList<ProcessSummaryLineInterface> target, ProcessSummaryLine... lines)
   {
      for(ProcessSummaryLine line : lines)
      {
         if(line.getCount() == null)
         {
            target.add(line);
         }
         else
         {
            line.addSelfToListIfAnyCount(target);
         }
      }
   }
}
