package iterator;

import bufmgr.PageNotReadException;
import diskmgr.Stream;
import global.EID;
import global.QID;
import global.SystemDefs;
import heap.InvalidTupleSizeException;
import heap.InvalidTypeException;
import index.IndexException;
import labelheap.Label;
import labelheap.LabelHeapFile;
import quadrupleheap.Quadruple;

import java.io.IOException;
import java.util.ArrayList;

//TODO: Change it to BPIterator
public class BP_Triple_Join implements BPIterator {

    int amt_of_mem;
    int num_left_nodes;
    BPIterator left_itr;
    int BPJoinNodePosition;
    int JoinOnSubjectorObject;
    String RightSubjectFilter;
    String RightPredicateFilter;
    String RightObjectFilter;
    double RightConfidenceFilter;
    int [] LeftOutNodePosition;
    int OutputRightSubject;
    int OutputRightObject;

    private boolean done;
    private boolean getFromOuter;
    private Stream innerStream;
    private BasicPattern outer_bp;
    private Quadruple inner_qr;

    BP_Triple_Join( int amt_of_mem,
                    int num_left_nodes,
                    BPIterator left_itr,
                    int BPJoinNodePosition,
                    int JoinOnSubjectorObject,
                    String RightSubjectFilter,
                    String RightPredicateFilter,
                    String RightObjectFilter,
                    double RightConfidenceFilter,
                    int [] LeftOutNodePositions,
                    int OutputRightSubject,
                    int OutputRightObject){

        this.amt_of_mem = amt_of_mem;
        this.num_left_nodes = num_left_nodes;
        this.left_itr = left_itr;
        this.BPJoinNodePosition = BPJoinNodePosition;
        this.JoinOnSubjectorObject = JoinOnSubjectorObject;
        this.RightSubjectFilter = new String(RightSubjectFilter);
        this.RightObjectFilter = new String(RightObjectFilter);
        this.RightPredicateFilter = new String(RightPredicateFilter);
        this.RightConfidenceFilter = RightConfidenceFilter;
        this.LeftOutNodePosition = LeftOutNodePosition;
        this.OutputRightSubject = OutputRightSubject;
        this.OutputRightObject = OutputRightObject;

        this.getFromOuter = true;
        this.innerStream = null;
        this.outer_bp = null;
        this.inner_qr = null;
        this.done = false;
    }


    //TODO: Change Tuple to BasicPattern
    @Override
    public BasicPattern get_next() throws IOException, JoinsException, IndexException, InvalidTupleSizeException, InvalidTypeException, PageNotReadException, TupleUtilsException, PredEvalException, SortException, LowMemException, UnknowAttrType, UnknownKeyTypeException, Exception {
        //if the scan is done, return null
        if (done) return null;

        do {

            if (getFromOuter == true) {
                getFromOuter = false;
                if (innerStream != null) {
                    innerStream.closeStream();
                    innerStream = null;
                }

                try {
                    //TODO: check the orderType and bufPoolSize
                    innerStream = SystemDefs.JavabaseDB.openStream(7, RightSubjectFilter, RightPredicateFilter, RightObjectFilter, RightConfidenceFilter, this.amt_of_mem);
                } catch (Exception e) {
                    System.out.println("Open Stream failed during Triple Join: "+ e);
                }
                //if the scan on basicPattern is complete, return null
                if ((outer_bp = left_itr.get_next()) == null) {
                    done = true;
                    if (innerStream != null) {
                        innerStream.closeStream();
                        innerStream = null;
                    }
                    return null;
                }
            }

            //Fetch the inner quadruple
            QID qid = new QID();


            while ((inner_qr = innerStream.getNext(qid)) != null) {
                if (compareFilters() == true) {
                    double confidence = inner_qr.getConfidence();
                    ArrayList<EID> EIDs = new ArrayList<EID>();
                    EID outerEID = outer_bp.getEIDFld(BPJoinNodePosition);
                    EID innerEID = JoinOnSubjectorObject == 0 ? inner_qr.getSubjecqid() : inner_qr.getObjecqid();

                    double min_conf = Math.min(confidence, outer_bp.getDoubleFld(outer_bp.noOfFlds()));

                    if (outerEID.equals(innerEID)) {
                        BasicPattern bp = new BasicPattern();
                        for (int j = 0; j < LeftOutNodePosition.length; j++) {
                            EIDs.add(outer_bp.getEIDFld(LeftOutNodePosition[j]));
                        }

                        if (OutputRightSubject == 1) {
                            if (JoinOnSubjectorObject == 0) {

                                boolean isPresent = false;

                                for (int k = 0; k < LeftOutNodePosition.length; k++) {
                                    if (LeftOutNodePosition[k] == BPJoinNodePosition) {
                                        isPresent = true;
                                        break;
                                    }
                                }
                                if (!isPresent)
                                    EIDs.add(inner_qr.getSubjecqid());
                            } else {
                                EIDs.add(inner_qr.getSubjecqid());
                            }
                        }

                        if (OutputRightObject == 1) {
                            if (JoinOnSubjectorObject == 1) {

                                boolean isPresent = false;
                                for (int k = 0; k < LeftOutNodePosition.length; k++) {
                                    if (LeftOutNodePosition[k] == BPJoinNodePosition) {
                                        isPresent = true;
                                        break;
                                    }
                                }
                                if (!isPresent)
                                    EIDs.add(inner_qr.getObjecqid());
                            } else {
                                EIDs.add(inner_qr.getObjecqid());
                            }
                        }

                        if (EIDs.size() != 0) {
                            bp.setHdr((short) (EIDs.size() + 1));
                            for (int k = 0; k < EIDs.size(); k++) {
                                bp.setEIDFld(k + 1, EIDs.get(k));
                            }
                            bp.setDoubleFld(k + 1, min_conf);
                            return bp;
                        }
                    }
                }
            }
            getFromOuter = true;
        }while(true);
    }

    private boolean compareFilters() throws InvalidTupleSizeException, Exception{
        LabelHeapFile Entity_HF = SystemDefs.JavabaseDB.getEntityHandle();
        LabelHeapFile Predicate_HF = SystemDefs.JavabaseDB.getPredicateHandle();
        double confidence = 0;
        confidence = inner_qr.getConfidence();
        Label subject = Entity_HF.getLabel(inner_qr.getSubjecqid().returnLID());
        Label predicate = Predicate_HF.getLabel(inner_qr.getPredicateID().returnLID());
        Label object = Entity_HF.getLabel(inner_qr.getObjecqid().returnLID());
        boolean result = true;

        if(RightSubjectFilter.compareToIgnoreCase("null") != 0)
        {
            result = result & (RightSubjectFilter.compareTo(subject.getLabel()) == 0);
        }
        if(RightObjectFilter.compareToIgnoreCase("null") != 0)
        {
            result = result & (RightObjectFilter.compareTo(object.getLabel()) == 0 );
        }
        if(RightPredicateFilter.compareToIgnoreCase("null") != 0)
        {
            result = result & (RightPredicateFilter.compareTo(predicate.getLabel()) == 0 );
        }
        if(RightConfidenceFilter != 0)
        {
            result = result & (confidence >= RightConfidenceFilter);
        }
        return result;
    }

    @Override
    public void close() throws IOException, SortException {
        if (!closeFlag) {
            try {
                if(innerStream!=null) innerStream.closeStream();
                left_itr.close();
            }catch (Exception e) {
                System.out.println("Error in closing triple join iterator."+e);
            }
            closeFlag = true;
        }
    }
}