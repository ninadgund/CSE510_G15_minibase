package heap;

import diskmgr.Page;
import global.*;

import java.io.IOException;

interface  Filetype {
    int TEMP = 0;
    int ORDINARY = 1;

}
public class QuadrupleHeapFile implements Filetype, GlobalConst {

    PageId      _firstDirPageId;   // page number of header page
    int         _ftype;
    private     boolean     _file_deleted;
    private     String 	 _fileName;
    private static int tempfilecount = 0;

    public  QuadrupleHeapFile(String name)
            throws HFException,
            HFBufMgrException,
            HFDiskMgrException,
            IOException {
        // Give us a prayer of destructing cleanly if construction fails.
        _file_deleted = true;
        _fileName = null;

        if (name == null) {
            // If the name is NULL, allocate a temporary name
            // and no logging is required.
            _fileName = "tempQHeapFile";
            String useId = new String("user.name");
            String userAccName;
            userAccName = System.getProperty(useId);
            _fileName = _fileName + userAccName;

            String filenum = Integer.toString(tempfilecount);
            _fileName = _fileName + filenum;
            _ftype = TEMP;
            tempfilecount++;

        } else {
            _fileName = name;
            _ftype = ORDINARY;
        }
        Page apage = new Page();
        _firstDirPageId = null;
        if (_ftype == ORDINARY)
            _firstDirPageId = get_file_entry(_fileName);

        if (_firstDirPageId == null) {
            // file doesn't exist. First create it.
            _firstDirPageId = newPage(apage, 1);
            // check error
            if (_firstDirPageId == null)
                throw new HFException(null, "can't new page");

            add_file_entry(_fileName, _firstDirPageId);
            // check error(new exception: Could not add file entry

            HFPage firstDirPage = new HFPage();
            firstDirPage.init(_firstDirPageId, apage);
            PageId pageId = new PageId(INVALID_PAGE);

            firstDirPage.setNextPage(pageId);
            firstDirPage.setPrevPage(pageId);
            unpinPage(_firstDirPageId, true /*dirty*/);
        }
        _file_deleted = false;
    }

    public int getQuadrupleCnt()
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            HFDiskMgrException,
            HFBufMgrException,
            IOException {
        int answer = 0;
        PageId currentDirPageId = new PageId(_firstDirPageId.pid);

        PageId nextDirPageId = new PageId(0);

        HFPage currentDirPage = new HFPage();
        while(currentDirPageId.pid != INVALID_PAGE)
        {
            pinPage(currentDirPageId, currentDirPage, false);

            QID qid = new QID();
            Quadruple aquad;

            for (qid = currentDirPage.firstRecord();
                 qid != null;	// rid==NULL means no more record
                 qid = currentDirPage.nextRecord(qid))
            {
                aquad = currentDirPage.getRecord(qid);
                DataPageInfo dpinfo = new DataPageInfo(aquad);
                answer += dpinfo.recct;
            }
            // ASSERTIONS: no more record
            // - we have read all datapage records on
            //   the current directory page.
            nextDirPageId = currentDirPage.getNextPage();
            unpinPage(currentDirPageId, false /*undirty*/);
            currentDirPageId.pid = nextDirPageId.pid;
        }
        // ASSERTIONS:
        // - if error, exceptions
        // - if end of heapfile reached: currentDirPageId == INVALID_PAGE
        // - if not yet end of heapfile: currentDirPageId valid
        return answer;
    }

    public RID insertQuadruple(byte[] quadruplePtr)
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            SpaceNotAvailableException,
            HFException,
            HFBufMgrException,
            HFDiskMgrException,
            IOException
    {
        int dpinfoLen = 0;
        int quadLen = quadruplePtr.length;
        boolean found;
        QID currentDataPageQid = new QID();
        Page pageinbuffer = new Page();
        HFPage currentDirPage = new HFPage();
        HFPage currentDataPage = new HFPage();

        HFPage nextDirPage = new HFPage();
        PageId currentDirPageId = new PageId(_firstDirPageId.pid);
        PageId nextDirPageId = new PageId();  // OK

        pinPage(currentDirPageId, currentDirPage, false/*Rdisk*/);

        found = false;
        Quadruple aquad;
        DataPageInfo dpinfo = new DataPageInfo();
        while (found == false)
        { //Start While01
            // look for suitable dpinfo-struct
            for (currentDataPageQid = currentDirPage.firstRecord();
                 currentDataPageQid != null;
                 currentDataPageQid =
                         currentDirPage.nextRecord(currentDataPageQid))
            {
                aquad = currentDirPage.getRecord(currentDataPageQid);

                dpinfo = new DataPageInfo(aquad);

                // need check the record length == DataPageInfo'slength

                if(quadLen <= dpinfo.availspace)
                {
                    found = true;
                    break;
                }
            }

            // two cases:
            // (1) found == true:
            //     currentDirPage has a datapagerecord which can accomodate
            //     the record which we have to insert
            // (2) found == false:
            //     there is no datapagerecord on the current directory page
            //     whose corresponding datapage has enough space free
            //     several subcases: see below
            if(found == false)
            { //Start IF01
                // case (2)

                //System.out.println("no datapagerecord on the current directory is OK");
                //System.out.println("dirpage availspace "+currentDirPage.available_space());

                // on the current directory page is no datapagerecord which has
                // enough free space
                //
                // two cases:
                //
                // - (2.1) (currentDirPage->available_space() >= sizeof(DataPageInfo):
                //         if there is enough space on the current directory page
                //         to accomodate a new datapagerecord (type DataPageInfo),
                //         then insert a new DataPageInfo on the current directory
                //         page
                // - (2.2) (currentDirPage->available_space() <= sizeof(DataPageInfo):
                //         look at the next directory page, if necessary, create it.

                if(currentDirPage.available_space() >= dpinfo.size)
                {
                    //Start IF02
                    // case (2.1) : add a new data page record into the
                    //              current directory page
                    currentDataPage = _newDatapage(dpinfo);
                    // currentDataPage is pinned! and dpinfo->pageId is also locked
                    // in the exclusive mode

                    // didn't check if currentDataPage==NULL, auto exception


                    // currentDataPage is pinned: insert its record
                    // calling a HFPage function



                    aquad = dpinfo.convertToQuadruple();

                    byte [] tmpData = aquad.getQuadrupleByteArray();
                    currentDataPageQid = currentDirPage.insertRecord(tmpData);

                    QID tmpqid = currentDirPage.firstRecord();


                    // need catch error here!
                    if(currentDataPageQid == null)
                        throw new HFException(null, "no space to insert rec.");

                    // end the loop, because a new datapage with its record
                    // in the current directorypage was created and inserted into
                    // the heapfile; the new datapage has enough space for the
                    // record which the user wants to insert

                    found = true;

                } //end of IF02
                else
                {  //Start else 02
                    // case (2.2)
                    nextDirPageId = currentDirPage.getNextPage();
                    // two sub-cases:
                    //
                    // (2.2.1) nextDirPageId != INVALID_PAGE:
                    //         get the next directory page from the buffer manager
                    //         and do another look
                    // (2.2.2) nextDirPageId == INVALID_PAGE:
                    //         append a new directory page at the end of the current
                    //         page and then do another loop

                    if (nextDirPageId.pid != INVALID_PAGE)
                    { //Start IF03
                        // case (2.2.1): there is another directory page:
                        unpinPage(currentDirPageId, false);

                        currentDirPageId.pid = nextDirPageId.pid;

                        pinPage(currentDirPageId,
                                currentDirPage, false);



                        // now go back to the beginning of the outer while-loop and
                        // search on the current directory page for a suitable datapage
                    } //End of IF03
                    else
                    {  //Start Else03
                        // case (2.2): append a new directory page after currentDirPage
                        //             since it is the last directory page
                        nextDirPageId = newPage(pageinbuffer, 1);
                        // need check error!
                        if(nextDirPageId == null)
                            throw new HFException(null, "can't new page");

                        // initialize new directory page
                        nextDirPage.init(nextDirPageId, pageinbuffer);
                        PageId temppid = new PageId(INVALID_PAGE);
                        nextDirPage.setNextPage(temppid);
                        nextDirPage.setPrevPage(currentDirPageId);

                        // update current directory page and unpin it
                        // currentDirPage is already locked in the Exclusive mode
                        currentDirPage.setNextPage(nextDirPageId);
                        unpinPage(currentDirPageId, true/*dirty*/);

                        currentDirPageId.pid = nextDirPageId.pid;
                        currentDirPage = new HFPage(nextDirPage);

                        // remark that MINIBASE_BM->newPage already
                        // pinned the new directory page!
                        // Now back to the beginning of the while-loop, using the
                        // newly created directory page.

                    } //End of else03
                } // End of else02
                // ASSERTIONS:
                // - if found == true: search will end and see assertions below
                // - if found == false: currentDirPage, currentDirPageId
                //   valid and pinned

            }//end IF01
            else
            { //Start else01
                // found == true:
                // we have found a datapage with enough space,
                // but we have not yet pinned the datapage:

                // ASSERTIONS:
                // - dpinfo valid

                // System.out.println("find the dirpagerecord on current page");

                pinPage(dpinfo.pageId, currentDataPage, false);
                //currentDataPage.openHFpage(pageinbuffer);


            }//End else01
        } //end of While01

        // ASSERTIONS:
        // - currentDirPageId, currentDirPage valid and pinned
        // - dpinfo.pageId, currentDataPageRid valid
        // - currentDataPage is pinned!

        if ((dpinfo.pageId).pid == INVALID_PAGE) // check error!
            throw new HFException(null, "invalid PageId");

        if (!(currentDataPage.available_space() >= recLen))
            throw new SpaceNotAvailableException(null, "no available space");

        if (currentDataPage == null)
            throw new HFException(null, "can't find Data page");


        QID qid;
        qid = currentDataPage.insertRecord(quadruplePtr);

        dpinfo.recct++;
        dpinfo.availspace = currentDataPage.available_space();


        unpinPage(dpinfo.pageId, true /* = DIRTY */);

        // DataPage is now released
        aquad = currentDirPage.returnRecord(currentDataPageQid);
        DataPageInfo dpinfo_ondirpage = new DataPageInfo(aquad);


        dpinfo_ondirpage.availspace = dpinfo.availspace;
        dpinfo_ondirpage.recct = dpinfo.recct;
        dpinfo_ondirpage.pageId.pid = dpinfo.pageId.pid;
        dpinfo_ondirpage.flushToTuple();


        unpinPage(currentDirPageId, true /* = DIRTY */);


        return qid;

    }

    public boolean deleteQuadruple(QID qid)
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            HFException,
            HFBufMgrException,
            HFDiskMgrException,
            Exception {
        boolean status;
        HFPage currentDirPage = new HFPage();
        PageId currentDirPageId = new PageId();
        HFPage currentDataPage = new HFPage();
        PageId currentDataPageId = new PageId();
        QID currentDataPageQid = new QID();

        status = _findDataPage(qid,
                currentDirPageId, currentDirPage,
                currentDataPageId, currentDataPage,
                currentDataPageQid);
        if(status != true) return status;	// record not found

        // ASSERTIONS:
        // - currentDirPage, currentDirPageId valid and pinned
        // - currentDataPage, currentDataPageid valid and pinned

        // get datapageinfo from the current directory page:
        Quadruple aquad;
        aquad = currentDirPage.returnRecord(currentDataPageQid);
        DataPageInfo pdpinfo = new DataPageInfo(aquad);

        // delete the record on the datapage
        currentDataPage.deleteRecord(qid);

        pdpinfo.recct--;
        pdpinfo.flushToTuple();	//Write to the buffer pool
        if (pdpinfo.recct >= 1) {
            // more records remain on datapage so it still hangs around.
            // we just need to modify its directory entry
            pdpinfo.availspace = currentDataPage.available_space();
            pdpinfo.flushToTuple();
            unpinPage(currentDataPageId, true /* = DIRTY*/);
            unpinPage(currentDirPageId, true /* = DIRTY */);
        }
        else {
            // the record is already deleted:
            // we're removing the last record on datapage so free datapage
            // also, free the directory page if
            //   a) it's not the first directory page, and
            //   b) we've removed the last DataPageInfo record on it.

            // delete empty datapage: (does it get unpinned automatically? -NO, Ranjani)
            unpinPage(currentDataPageId, false /*undirty*/);
            freePage(currentDataPageId);

            // delete corresponding DataPageInfo-entry on the directory page:
            // currentDataPageRid points to datapage (from for loop above)
            currentDirPage.deleteRecord(currentDataPageQid);

            // now check whether the directory page is empty:
            currentDataPageQid = currentDirPage.firstRecord();

            // st == OK: we still found a datapageinfo record on this directory page
            PageId pageId;
            pageId = currentDirPage.getPrevPage();
            if((currentDataPageQid == null)&&(pageId.pid != INVALID_PAGE))
            {
                // the directory-page is not the first directory page and it is empty:
                // delete it

                // point previous page around deleted page:

                HFPage prevDirPage = new HFPage();
                pinPage(pageId, prevDirPage, false);
                pageId = currentDirPage.getNextPage();
                prevDirPage.setNextPage(pageId);
                pageId = currentDirPage.getPrevPage();
                unpinPage(pageId, true /* = DIRTY */);

                // set prevPage-pointer of next Page
                pageId = currentDirPage.getNextPage();
                if(pageId.pid != INVALID_PAGE)
                {
                    HFPage nextDirPage = new HFPage();
                    pageId = currentDirPage.getNextPage();
                    pinPage(pageId, nextDirPage, false);
                    //nextDirPage.openHFpage(apage);
                    pageId = currentDirPage.getPrevPage();
                    nextDirPage.setPrevPage(pageId);
                    pageId = currentDirPage.getNextPage();
                    unpinPage(pageId, true /* = DIRTY */);
                }
                // delete empty directory page: (automatically unpinned?)
                unpinPage(currentDirPageId, false/*undirty*/);
                freePage(currentDirPageId);
            }
            else
            {
                // either (the directory page has at least one more datapagerecord
                // entry) or (it is the first directory page):
                // in both cases we do not delete it, but we have to unpin it:
                unpinPage(currentDirPageId, true /* == DIRTY */);
            }
        }
        return true;
    }

    public boolean updateQuadruple(QID qid, Quadruple newquadruple)
            throws InvalidSlotNumberException,
            InvalidUpdateException,
            InvalidTupleSizeException,
            HFException,
            HFDiskMgrException,
            HFBufMgrException,
            Exception
    {
        boolean status;
        HFPage dirPage = new HFPage();
        PageId currentDirPageId = new PageId();
        HFPage dataPage = new HFPage();
        PageId currentDataPageId = new PageId();
        QID currentDataPageQid = new QID();

        status = _findDataPage(qid,
                currentDirPageId, dirPage,
                currentDataPageId, dataPage,
                currentDataPageQid);

        if(status != true) return status;	// record not found
        Quadruple aquad = new Quadruple();
        aquad = dataPage.returnRecord(qid);

        // new copy of this record fits in old space;
        aquad.quadrupleCopy(newquadruple);
        unpinPage(currentDataPageId, true /* = DIRTY */);

        unpinPage(currentDirPageId, false /*undirty*/);


        return true;
    }

    public  Tuple getQuadruple(QID qid)
            throws InvalidSlotNumberException,
            InvalidTupleSizeException,
            HFException,
            HFDiskMgrException,
            HFBufMgrException,
            Exception
    {
        boolean status;
        HFPage dirPage = new HFPage();
        PageId currentDirPageId = new PageId();
        HFPage dataPage = new HFPage();
        PageId currentDataPageId = new PageId();
        QID currentDataPageRid = new QID();

        status = _findDataPage(qid,
                currentDirPageId, dirPage,
                currentDataPageId, dataPage,
                currentDataPageRid);

        if(status != true) return null; // record not found

        Quadruple aquad = new Quadruple();
        aquad = dataPage.getRecord(qid);

        /*
         * getRecord has copied the contents of qid into recPtr and fixed up
         * recLen also.  We simply have to unpin dirpage and datapage which
         * were originally pinned by _findDataPage.
         */
        unpinPage(currentDataPageId,false /*undirty*/);
        unpinPage(currentDirPageId,false /*undirty*/);

        return  aquad;  //(true?)OK, but the caller need check if atuple==NULL
    }

    public Scan openScan()
            throws InvalidTupleSizeException,
            IOException
    {
        Scan newscan = new Scan(this);
        return newscan;
    }

    public void deleteFile()
            throws InvalidSlotNumberException,
            FileAlreadyDeletedException,
            InvalidTupleSizeException,
            HFBufMgrException,
            HFDiskMgrException,
            IOException
    {
        if(_file_deleted )
            throw new FileAlreadyDeletedException(null, "file already deleted");
        // Mark the deleted flag (even if it doesn't get all the way done).
        _file_deleted = true;

        // Deallocate all data pages
        PageId currentDirPageId = new PageId();
        currentDirPageId.pid = _firstDirPageId.pid;
        PageId nextDirPageId = new PageId();
        nextDirPageId.pid = 0;
        Page pageinbuffer = new Page();
        HFPage currentDirPage =  new HFPage();
        Quadruple aquad;

        pinPage(currentDirPageId, currentDirPage, false);

        QID qid = new QID();
        while(currentDirPageId.pid != INVALID_PAGE)
        {
            for(qid = currentDirPage.firstRecord(); //TO-Modify
                qid != null;
                qid = currentDirPage.nextRecord(qid)) //TO-Modify
            {
                aquad = currentDirPage.getRecord(qid); //TO-Modify
                DataPageInfo dpinfo = new DataPageInfo( aquad); //Change class
                freePage(dpinfo.pageId);
            }
            // ASSERTIONS:
            // - we have freePage()'d all data pages referenced by
            // the current directory page.
            nextDirPageId = currentDirPage.getNextPage();
            freePage(currentDirPageId);
            currentDirPageId.pid = nextDirPageId.pid;

            if (nextDirPageId.pid != INVALID_PAGE)
            {
                pinPage(currentDirPageId, currentDirPage, false);
                //currentDirPage.openHFpage(pageinbuffer);
            }
        }
        delete_file_entry( _fileName );
    }

    private void pinPage(PageId pageno, Page page, boolean emptyPage)
            throws HFBufMgrException {
        try {
            SystemDefs.JavabaseBM.pinPage(pageno, page, emptyPage);
        }
        catch (Exception e) {
            throw new HFBufMgrException(e,"Heapfile.java: pinPage() failed");
        }

    } // end of pinPage

    private void unpinPage(PageId pageno, boolean dirty)
            throws HFBufMgrException {
        try {
            SystemDefs.JavabaseBM.unpinPage(pageno, dirty);
        }
        catch (Exception e) {
            throw new HFBufMgrException(e,"Heapfile.java: unpinPage() failed");
        }

    } // end of unpinPage

    private void freePage(PageId pageno)
            throws HFBufMgrException {
        try {
            SystemDefs.JavabaseBM.freePage(pageno);
        }
        catch (Exception e) {
            throw new HFBufMgrException(e,"Heapfile.java: freePage() failed");
        }

    } // end of freePage

    private PageId newPage(Page page, int num)
            throws HFBufMgrException {

        PageId tmpId = new PageId();

        try {
            tmpId = SystemDefs.JavabaseBM.newPage(page,num);
        }
        catch (Exception e) {
            throw new HFBufMgrException(e,"Heapfile.java: newPage() failed");
        }

        return tmpId;

    } // end of newPage

    private PageId get_file_entry(String filename)
            throws HFDiskMgrException {

        PageId tmpId = new PageId();

        try {
            tmpId = SystemDefs.JavabaseDB.get_file_entry(filename);
        }
        catch (Exception e) {
            throw new HFDiskMgrException(e,"Heapfile.java: get_file_entry() failed");
        }

        return tmpId;

    } // end of get_file_entry

    private void add_file_entry(String filename, PageId pageno)
            throws HFDiskMgrException {

        try {
            SystemDefs.JavabaseDB.add_file_entry(filename,pageno);
        }
        catch (Exception e) {
            throw new HFDiskMgrException(e,"Heapfile.java: add_file_entry() failed");
        }

    } // end of add_file_entry

    private void delete_file_entry(String filename)
            throws HFDiskMgrException {

        try {
            SystemDefs.JavabaseDB.delete_file_entry(filename);
        }
        catch (Exception e) {
            throw new HFDiskMgrException(e,"Heapfile.java: delete_file_entry() failed");
        }

    } // end of delete_file_entry

}
